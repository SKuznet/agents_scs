package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FooterLine;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.example.agenthub.TestJws;
import org.example.agenthub.events.Event;
import org.example.agenthub.events.EventRepository;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Manual SQUASH merge of §7.7 and invariant D8. */
class MergeApiTest extends GitIntegrationTest {

    @Autowired
    EventRepository events;

    @Autowired
    PullRequestRepository pulls;

    String owner;
    String bob;
    String carol;
    String erin;
    String adam;
    long repoId;

    @BeforeEach
    void repository() {
        owner = unique("olga");
        bob = unique("bob");
        carol = unique("carol");
        erin = unique("erin");
        adam = unique("adam");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", carol, "WRITE");
        grant(owner, "proj", erin, "WRITE");
        grant(owner, "proj", adam, "ADMIN");
        repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, "proj").orElseThrow().getId();
    }

    String api(int number) {
        return "/v1/repos/" + owner + "/proj/pulls/" + number;
    }

    /** Opens a PR by {@code author} with one new file, auto-merge off (manual merges here); returns its head. */
    RevCommit openPr(String author, String name, String file, String... options) throws Exception {
        try (Git clone = cloneAs(author, owner, "proj")) {
            RevCommit head = commitFile(clone, file, "content of " + file);
            List<String> all = new java.util.ArrayList<>(List.of(options));
            all.add("no-auto-merge");
            push(clone, author, "HEAD:refs/heads/pr/" + name, all.toArray(String[]::new));
            return head;
        }
    }

    void branch(String name, String file, String content) throws Exception {
        try (Git clone = cloneAs(carol, owner, "proj")) {
            if (file != null) {
                commitFile(clone, file, content);
            }
            push(clone, carol, "+HEAD:refs/heads/" + name);
        }
    }

    ResponseEntity<Map> approve(String agent, int number, int rev) {
        return call(HttpMethod.POST, api(number) + "/reviews", agent, Map.of("rev", rev, "verdict", "APPROVE"),
                Map.class);
    }

    ResponseEntity<Map> merge(String agent, int number, String expectedHead) {
        return merge(agent, number, Map.of("expectedHeadSha", expectedHead));
    }

    ResponseEntity<Map> merge(String agent, int number, Map<String, ?> body) {
        return call(HttpMethod.POST, api(number) + "/merge", agent, body, Map.class);
    }

    static Map<String, Object> merged(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("state", "MERGED");
        return response.getBody();
    }

    static void refused(ResponseEntity<Map> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("code", code).containsKey("hint");
    }

    @Test
    void approvedPullRequestIsSquashedIntoTheTargetWithTrailers() throws Exception {
        String token = TestJws.sign(new JWTClaimsSet.Builder().subject(bob)
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("model", "claude-opus-5-5").claim("session", "run-8f2a").claim("operator", "team-x").build(),
                TestJws.SECRET, JWSAlgorithm.HS256);
        RevCommit head;
        try (Git clone = cloneAs(bob, owner, "proj")) {
            head = commitFile(clone, "src/Parser.java", "class Parser {}");
            clone.push().setCredentialsProvider(new UsernamePasswordCredentialsProvider("x", token))
                    .setRefSpecs(new RefSpec("HEAD:refs/heads/pr/fix-parser"))
                    .setPushOptions(List.of("title=Fix parser", "description=Handles empty input",
                            "no-auto-merge")).call();
        }
        approve(carol, 1, 1);
        String tipBefore;
        try (Repository server = serverRepo(owner, "proj")) {
            tipBefore = server.resolve("refs/heads/main").name();
        }

        Map<String, Object> pr = merged(merge(carol, 1, head.name()));

        assertThat(pr).containsEntry("mergedBy", carol).containsEntry("autoMerged", false);
        try (Repository server = serverRepo(owner, "proj")) {
            RevCommit squash = server.parseCommit(server.resolve("refs/heads/main"));
            assertThat(squash.name()).isEqualTo(pr.get("mergeCommitSha"));
            assertThat(squash.getParentCount()).isEqualTo(1);
            assertThat(squash.getParent(0).name()).isEqualTo(tipBefore);
            assertThat(server.resolve("refs/heads/main:src/Parser.java")).isNotNull();
            assertThat(squash.getAuthorIdent().getName()).isEqualTo(bob);
            assertThat(squash.getAuthorIdent().getEmailAddress()).isEqualTo(bob + "@agents.agenthub");
            assertThat(squash.getCommitterIdent().getName()).isEqualTo("agenthub");
            assertThat(squash.getCommitterIdent().getEmailAddress()).isEqualTo("noreply@agents.agenthub");
            assertThat(squash.getFullMessage()).startsWith("Fix parser (#1)\n\nHandles empty input\n\n");
            assertThat(squash.getFooterLines()).extracting(FooterLine::getKey, FooterLine::getValue)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("AgentHub-PR", "#1"),
                            org.assertj.core.groups.Tuple.tuple("AgentHub-Session", "run-8f2a"),
                            org.assertj.core.groups.Tuple.tuple("AgentHub-Model", "claude-opus-5-5"),
                            org.assertj.core.groups.Tuple.tuple("AgentHub-Operator", "team-x"),
                            org.assertj.core.groups.Tuple.tuple("Reviewed-By",
                                    carol + " <" + carol + "@agents.agenthub>"));
            assertThat(server.exactRef("refs/pull/1/head").getObjectId()).isEqualTo(head.getId());
        }
        PullRequest stored = pulls.findByRepoIdAndNumber(repoId, 1).orElseThrow();
        assertThat(stored.getMergingCommitSha()).isNull();
        assertThat(stored.getMergedAt()).isNotNull();
        await().until(() -> events.findByRepoIdOrderById(repoId).stream().map(Event::getType).toList(),
                types -> types.contains("pr.merged"));
    }

    @Test
    void mergingAgainReturnsTheMergedPullRequest() throws Exception {
        RevCommit head = openPr(bob, "fix", "a.txt");
        approve(carol, 1, 1);
        Map<String, Object> first = merged(merge(carol, 1, head.name()));

        Map<String, Object> again = merged(merge(erin, 1, head.name()));

        assertThat(again.get("mergeCommitSha")).isEqualTo(first.get("mergeCommitSha"));
        assertThat(again).containsEntry("mergedBy", carol);
    }

    @Test
    void sameNameAfterMergeOpensANewPullRequest() throws Exception {
        RevCommit head = openPr(bob, "fix", "a.txt");
        approve(carol, 1, 1);
        merged(merge(carol, 1, head.name()));

        try (Git clone = cloneAs(bob, owner, "proj")) {
            commitFile(clone, "b.txt", "b");
            PushResult result = clone.push().setCredentialsProvider(credentials(bob))
                    .setRefSpecs(new RefSpec("HEAD:refs/heads/pr/fix")).setPushOptions(List.of("no-auto-merge"))
                    .call().iterator().next();

            assertThat(result.getMessages()).contains("Created PR #2")
                    .contains("[agenthub] PR #1 was merged; this is a new PR #2");
        }
    }

    @Test
    void mergeStrategyKeepsTheHistoryOfThePullRequest() throws Exception {
        RevCommit head = openPr(bob, "fix", "a.txt", "title=Add a");
        approve(carol, 1, 1);
        String tipBefore;
        try (Repository server = serverRepo(owner, "proj")) {
            tipBefore = server.resolve("refs/heads/main").name();
        }

        Map<String, Object> pr = merged(merge(carol, 1, Map.of("expectedHeadSha", head.name(), "strategy", "merge")));

        try (Repository server = serverRepo(owner, "proj")) {
            RevCommit merge = server.parseCommit(server.resolve("refs/heads/main"));
            assertThat(merge.name()).isEqualTo(pr.get("mergeCommitSha"));
            assertThat(merge.getParents()).extracting(RevCommit::name).containsExactly(tipBefore, head.name());
            assertThat(merge.getFullMessage()).startsWith("Merge PR #1: Add a\n\n");
            assertThat(merge.getFooterLines()).extracting(FooterLine::getKey)
                    .containsExactly("AgentHub-PR", "Reviewed-By");
        }
    }

    @Test
    void targetMayHaveMovedOnWithoutConflicts() throws Exception {
        branch("dev", null, null);
        RevCommit head = openPr(bob, "fix", "a.txt", "target=dev");
        branch("dev", "other.txt", "other");
        approve(carol, 1, 1);

        merged(merge(carol, 1, head.name()));

        try (Repository server = serverRepo(owner, "proj")) {
            assertThat(server.resolve("refs/heads/dev:a.txt")).isNotNull();
            assertThat(server.resolve("refs/heads/dev:other.txt")).isNotNull();
        }
    }

    @Nested
    class InvariantD8 {

        @Test
        void proposerCannotMergeWithoutApprovalIntoTheProtectedDefaultBranch() throws Exception {
            RevCommit head = openPr(bob, "fix", "a.txt");

            ResponseEntity<Map> refused = merge(carol, 1, head.name());

            refused(refused, HttpStatus.FORBIDDEN, "approvals_required");
            assertThat((String) refused.getBody().get("hint")).contains("\"verdict\":\"APPROVE\"");
        }

        @Test
        void proposerNeedsAnApprovalEvenIntoAnUnprotectedBranch() throws Exception {
            branch("dev", null, null);
            RevCommit head = openPr(bob, "fix", "a.txt", "target=dev");

            refused(merge(carol, 1, head.name()), HttpStatus.FORBIDDEN, "approvals_required");

            approve(erin, 1, 1);
            merged(merge(carol, 1, head.name()));
        }

        @Test
        void changingTheTargetDropsTheApproval() throws Exception {
            branch("dev", null, null);
            RevCommit head = openPr(bob, "fix", "a.txt");
            approve(carol, 1, 1);

            call(HttpMethod.PATCH, api(1), bob, Map.of("target", "dev"), Map.class);

            refused(merge(carol, 1, head.name()), HttpStatus.FORBIDDEN, "approvals_required");
        }

        @Test
        void retargetingByPushDropsTheApprovalToo() throws Exception {
            branch("dev", null, null);
            RevCommit head = openPr(bob, "fix", "a.txt");
            approve(carol, 1, 1);
            try (Git clone = cloneAs(bob, owner, "proj")) {
                clone.fetch().setCredentialsProvider(credentials(bob)).setRefSpecs("refs/pull/1/head:refs/pr1").call();
                clone.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("refs/pr1").call();
                push(clone, bob, "HEAD:refs/heads/pr/fix", "target=dev", "no-auto-merge");
            }

            refused(merge(carol, 1, head.name()), HttpStatus.FORBIDDEN, "approvals_required");
        }

        @Test
        void approvalOfAnAgentWhoLostWriteDoesNotCount() throws Exception {
            RevCommit head = openPr(bob, "fix", "a.txt");
            approve(carol, 1, 1);

            grant(owner, "proj", carol, "READ");

            refused(merge(erin, 1, head.name()), HttpStatus.FORBIDDEN, "approvals_required");
        }

        @Test
        void requestChangesBlocksTheMerge() throws Exception {
            RevCommit head = openPr(bob, "fix", "a.txt");
            approve(carol, 1, 1);
            call(HttpMethod.POST, api(1) + "/reviews", erin, Map.of("rev", 1, "verdict", "REQUEST_CHANGES"),
                    Map.class);

            ResponseEntity<Map> refused = merge(carol, 1, head.name());

            refused(refused, HttpStatus.FORBIDDEN, "approvals_required");
            assertThat((String) refused.getBody().get("hint")).contains(erin);
        }

        @Test
        void writerAuthorNeedsOnlyWhatTheBranchRequires() throws Exception {
            branch("dev", null, null);
            RevCommit dev = openPr(carol, "dev-change", "d.txt", "target=dev");
            RevCommit main = openPr(carol, "main-change", "m.txt");

            merged(merge(carol, 1, dev.name()));
            refused(merge(carol, 2, main.name()), HttpStatus.FORBIDDEN, "approvals_required");
        }

        @Test
        void adminMayBypassProtectionOthersMayNot() throws Exception {
            RevCommit head = openPr(bob, "fix", "a.txt");

            refused(merge(carol, 1, Map.of("expectedHeadSha", head.name(), "bypassProtection", true)),
                    HttpStatus.FORBIDDEN, "insufficient_role");
            merged(merge(adam, 1, Map.of("expectedHeadSha", head.name(), "bypassProtection", true)));

            await().until(() -> events.findByRepoIdOrderById(repoId).stream()
                    .filter(e -> e.getType().equals("pr.merged")).toList(), list -> !list.isEmpty());
            assertThat(events.findByRepoIdOrderById(repoId).stream().filter(e -> e.getType().equals("pr.merged"))
                    .findFirst().orElseThrow().getPayload()).contains("\"bypassProtection\":true");
        }

        @Test
        void soleWriterIsToldHowToProceed() throws Exception {
            String solo = unique("solo");
            createRepo(solo, "alone");
            RevCommit head;
            try (Git clone = cloneAs(solo, solo, "alone")) {
                head = commitFile(clone, "a.txt", "a");
                push(clone, solo, "HEAD:refs/heads/pr/fix");
            }

            ResponseEntity<Map> refused = call(HttpMethod.POST, "/v1/repos/" + solo + "/alone/pulls/1/merge", solo,
                    Map.of("expectedHeadSha", head.name()), Map.class);

            refused(refused, HttpStatus.FORBIDDEN, "approvals_required");
            assertThat((String) refused.getBody().get("hint")).contains("you are the only WRITE member")
                    .contains("\"bypassProtection\":true");
        }
    }

    @Nested
    class Refusals {

        @Test
        void onlyWritersMerge() throws Exception {
            RevCommit head = openPr(bob, "fix", "a.txt");
            approve(carol, 1, 1);

            refused(merge(bob, 1, head.name()), HttpStatus.FORBIDDEN, "insufficient_role");
        }

        @Test
        void headMustBeTheReviewedOne() throws Exception {
            openPr(bob, "fix", "a.txt");
            approve(carol, 1, 1);

            ResponseEntity<Map> refused = merge(carol, 1, "0123456789012345678901234567890123456789");

            refused(refused, HttpStatus.CONFLICT, "stale_head");
            assertThat((String) refused.getBody().get("hint")).contains("GET " + api(1));
        }

        @Test
        void draftAndClosedPullRequestsAreNotMerged() throws Exception {
            RevCommit draft = openPr(bob, "draft", "a.txt", "draft");
            RevCommit closed = openPr(bob, "closed", "b.txt");
            call(HttpMethod.PATCH, api(2), bob, Map.of("state", "CLOSED"), Map.class);

            refused(merge(carol, 1, draft.name()), HttpStatus.CONFLICT, "draft");
            refused(merge(carol, 2, closed.name()), HttpStatus.CONFLICT, "not_open");
        }

        @Test
        void conflictsAreReportedWithPaths() throws Exception {
            branch("dev", null, null);
            RevCommit head = openPr(bob, "fix", "same.txt", "target=dev");
            branch("dev", "same.txt", "a different content");
            approve(carol, 1, 1);

            ResponseEntity<Map> refused = merge(carol, 1, head.name());

            refused(refused, HttpStatus.CONFLICT, "conflict");
            assertThat((String) refused.getBody().get("detail")).contains("same.txt");
            assertThat((String) refused.getBody().get("hint")).contains("git rebase origin/dev");
        }

        @Test
        void changesThatAlreadyLandedAreNotMergedAsAnEmptyCommit() throws Exception {
            branch("dev", null, null);
            RevCommit head = openPr(bob, "fix", "same.txt", "target=dev");
            try (Git clone = cloneAs(carol, owner, "proj")) {
                clone.checkout().setName("dev").setCreateBranch(true).setStartPoint("origin/dev").call();
                commitFile(clone, "same.txt", "content of same.txt");
                clone.commit().setAmend(true).setMessage("the same change, landed separately").setSign(false).call();
                push(clone, carol, "HEAD:refs/heads/dev");
            }
            approve(carol, 1, 1);
            String devBefore;
            try (Repository server = serverRepo(owner, "proj")) {
                devBefore = server.resolve("refs/heads/dev").name();
            }

            ResponseEntity<Map> refused = merge(carol, 1, head.name());

            refused(refused, HttpStatus.CONFLICT, "no_effective_changes");
            assertThat((String) refused.getBody().get("hint")).contains("\"state\":\"CLOSED\"");
            try (Repository server = serverRepo(owner, "proj")) {
                assertThat(server.resolve("refs/heads/dev").name()).isEqualTo(devBefore);
            }
        }

        @Test
        void mergeStrategyAndExpectedHeadAreValidated() throws Exception {
            RevCommit head = openPr(bob, "fix", "a.txt");
            approve(carol, 1, 1);

            refused(merge(carol, 1, Map.of("expectedHeadSha", head.name(), "strategy", "REBASE")),
                    HttpStatus.BAD_REQUEST, "validation_failed");
            refused(merge(carol, 1, Map.of("strategy", "SQUASH")), HttpStatus.BAD_REQUEST, "validation_failed");
        }
    }
}
