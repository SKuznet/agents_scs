package org.example.agenthub.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.example.agenthub.events.Event;
import org.example.agenthub.events.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class GitTransportTest extends GitIntegrationTest {

    @Autowired
    EventRepository events;

    @Test
    void readerClonesAndFetches() throws Exception {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "proj");
        grant(alice, "proj", bob, "READ");

        try (Git clone = cloneAs(bob, alice, "proj")) {
            assertThat(clone.getRepository().getWorkTree().toPath().resolve("AGENTS.md")).exists();
            assertThat(clone.fetch().setCredentialsProvider(credentials(bob)).call().getAdvertisedRefs()).isNotEmpty();
        }
    }

    @Test
    void anyAgentClonesAnInternalRepository() throws Exception {
        String alice = unique("alice");
        createRepo(alice, Map.of("name", "open", "visibility", "internal"));

        try (Git clone = cloneAs(unique("bob"), alice, "open")) {
            assertThat(Files.readString(clone.getRepository().getWorkTree().toPath().resolve("README.md")))
                    .contains("# open");
        }
    }

    @Test
    void strangerCannotClone() {
        String alice = unique("alice");
        createRepo(alice, "proj");

        assertThatThrownBy(() -> cloneAs(unique("eve"), alice, "proj")).isInstanceOf(InvalidRemoteException.class)
                .hasRootCauseInstanceOf(NoRemoteRepositoryException.class);
    }

    @Test
    void writerPushesANewBranchAndTheReflogNamesThePusher() throws Exception {
        String alice = unique("alice");
        String carol = unique("carol");
        createRepo(alice, "proj");
        grant(alice, "proj", carol, "WRITE");

        try (Git clone = cloneAs(carol, alice, "proj")) {
            RevCommit commit = commitFile(clone, "src/a.txt", "a");

            RemoteRefUpdate update = push(clone, carol, "HEAD:refs/heads/feature");

            assertThat(update.getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);
            try (Repository server = serverRepo(alice, "proj")) {
                assertThat(server.resolve("refs/heads/feature")).isEqualTo(commit.getId());
                List<ReflogEntry> reflog = server.getRefDatabase().getReflogReader("refs/heads/feature")
                        .getReverseEntries();
                assertThat(reflog).isNotEmpty();
                assertThat(reflog.get(0).getWho().getName()).isEqualTo(carol);
            }
        }
        List<?> branches = call(HttpMethod.GET, "/v1/repos/" + alice + "/proj/branches", alice, null, List.class)
                .getBody();
        assertThat(branches).hasSize(2);
    }

    @Test
    void acceptedPushIsRecordedAndRepositorySizeIsRefreshed() throws Exception {
        String alice = unique("alice");
        createRepo(alice, "proj");
        var repo = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "proj").orElseThrow();

        try (Git clone = cloneAs(alice, alice, "proj")) {
            RevCommit commit = commitFile(clone, "big.bin", "x".repeat(200_000) + Math.random());
            assertThat(push(clone, alice, "HEAD:refs/heads/topic").getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);
            push(clone, alice, "HEAD:refs/heads/main");

            List<Event> pushes = await().atMost(Duration.ofSeconds(5)).until(
                    () -> events.findByRepoIdOrderById(repo.getId()).stream()
                            .filter(e -> e.getType().equals("push")).toList(),
                    list -> !list.isEmpty());
            assertThat(pushes).hasSize(1);
            assertThat(pushes.get(0).getActor()).isEqualTo(alice);
            assertThat(pushes.get(0).getPayload()).contains("refs/heads/topic").contains(commit.name())
                    .doesNotContain("refs/heads/main");
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                repos.findById(repo.getId()).orElseThrow().getSizeBytes()).isGreaterThan(repo.getSizeBytes()));
    }

    @Test
    void readerPushIsRefused() throws Exception {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "proj");
        grant(alice, "proj", bob, "READ");

        try (Git clone = cloneAs(bob, alice, "proj")) {
            commitFile(clone, "b.txt", "b");

            assertThatThrownBy(() -> push(clone, bob, "HEAD:refs/heads/feature"))
                    .isInstanceOf(TransportException.class);
        }
    }
}
