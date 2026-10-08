package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.example.agenthub.events.Event;
import org.example.agenthub.events.EventRepository;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;

/** §7.4: mergeability follows the target in the background and is written with compare-and-set. */
class MergeabilityAsyncTest extends GitIntegrationTest {

    @Autowired
    EventRepository events;

    @Autowired
    PullRequestRepository pulls;

    @Autowired
    TransactionTemplate tx;

    String owner;
    String bob;
    String carol;
    long repoId;
    String api;

    @BeforeEach
    void repository() throws Exception {
        owner = unique("olga");
        bob = unique("bob");
        carol = unique("carol");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", carol, "WRITE");
        repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, "proj").orElseThrow().getId();
        api = "/v1/repos/" + owner + "/proj/pulls/";
        try (Git writer = cloneAs(carol, owner, "proj")) {
            push(writer, carol, "HEAD:refs/heads/dev");
        }
    }

    RevCommit openPr(String agent, String name, String file, String content) throws Exception {
        try (Git clone = cloneAs(agent, owner, "proj")) {
            RevCommit head = commitFile(clone, file, content);
            push(clone, agent, "HEAD:refs/heads/pr/" + name, "target=dev");
            return head;
        }
    }

    Map<String, Object> pr(int number) {
        return call(HttpMethod.GET, api + number, owner, null, Map.class).getBody();
    }

    void pushToDev(String file, String content) throws Exception {
        try (Git writer = cloneAs(carol, owner, "proj")) {
            writer.checkout().setName("dev").setCreateBranch(true).setStartPoint("origin/dev").call();
            commitFile(writer, file, content);
            push(writer, carol, "HEAD:refs/heads/dev");
        }
    }

    @Test
    void movingTheTargetRecomputesOpenPullRequests() throws Exception {
        openPr(bob, "fix", "shared.txt", "bob's version\n");
        assertThat(pr(1)).containsEntry("mergeable", "CLEAN");

        pushToDev("shared.txt", "carol's version\n");

        await().until(() -> "CONFLICT".equals(pr(1).get("mergeable")));
        assertThat(pr(1)).containsEntry("conflictPaths", List.of("shared.txt"));
        await().until(() -> events.findByRepoIdOrderById(repoId).stream().map(Event::getType).toList(),
                types -> types.contains("pr.mergeable_changed"));
    }

    @Test
    void mergingOnePullRequestRecomputesTheOthers() throws Exception {
        RevCommit first = openPr(bob, "one", "shared.txt", "one\n");
        openPr(carol, "two", "shared.txt", "two\n");
        call(HttpMethod.POST, api + "1/reviews", carol, Map.of("rev", 1, "verdict", "APPROVE"), Map.class);

        call(HttpMethod.POST, api + "1/merge", carol, Map.of("expectedHeadSha", first.name()), Map.class);

        await().until(() -> "CONFLICT".equals(pr(2).get("mergeable")));
    }

    @Test
    void retargetingRecomputesAgainstTheNewTarget() throws Exception {
        openPr(bob, "fix", "shared.txt", "bob's version\n");
        try (Git writer = cloneAs(carol, owner, "proj")) {
            commitFile(writer, "shared.txt", "release version\n");
            push(writer, carol, "HEAD:refs/heads/release");
        }

        call(HttpMethod.PATCH, api + "1", bob, Map.of("target", "release"), Map.class);

        await().until(() -> "CONFLICT".equals(pr(1).get("mergeable")));
    }

    @Test
    void staleResultsAreNotWritten() throws Exception {
        openPr(bob, "fix", "a.txt", "a\n");
        long id = pulls.findByRepoIdAndNumber(repoId, 1).orElseThrow().getId();

        Integer written = tx.execute(s -> pulls.recordMergeability(id, "0".repeat(40), Mergeable.CONFLICT,
                "f".repeat(40), "a.txt", null));

        assertThat(written).isZero();
        assertThat(pulls.findById(id).orElseThrow().getMergeable()).isEqualTo(Mergeable.CLEAN);
    }
}
