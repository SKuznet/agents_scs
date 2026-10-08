package org.example.agenthub.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.jgit.transport.ReceiveCommand.Result.NOT_ATTEMPTED;
import static org.eclipse.jgit.transport.ReceiveCommand.Result.REJECTED_OTHER_REASON;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.example.agenthub.access.Role;
import org.example.agenthub.config.JGitEnvironment;
import org.example.agenthub.repo.BranchProtection;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefPolicyHookTest {

    static final ObjectId ZERO = ObjectId.zeroId();

    InMemoryRepository db;
    TestRepository<InMemoryRepository> repo;
    RevCommit base;
    RevCommit next;
    RevCommit sideline;
    final List<String> messages = new ArrayList<>();

    @BeforeAll
    static void isolateJGit() {
        JGitEnvironment.install();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = new InMemoryRepository(new DfsRepositoryDescription("test"));
        repo = new TestRepository<>(db);
        base = repo.branch("refs/heads/main").commit().add("a.txt", "1").create();
        next = repo.commit().parent(base).add("a.txt", "2").create();
        sideline = repo.commit().parent(base).add("b.txt", "x").create();
        repo.update("refs/heads/feature", base);
    }

    RefPolicyHook hook(Role role) {
        return hook(role, "main", 50, 1000);
    }

    RefPolicyHook hook(Role role, String defaultBranch, int maxCommands, int maxRefs) {
        return hook(role, defaultBranch, maxCommands, maxRefs, false);
    }

    RefPolicyHook hook(Role role, String defaultBranch, int maxCommands, int maxRefs, boolean overQuota) {
        var protections = Map.of(defaultBranch, BranchProtection.forDefaultBranch(1L, defaultBranch));
        return new RefPolicyHook(new RefPolicyHook.Settings("bob", role, "alice/proj", defaultBranch, protections,
                maxCommands, maxRefs, overQuota, pullRequests));
    }

    record PrPush(String name, ObjectId head, List<String> options) {
    }

    final List<PrPush> prPushes = new ArrayList<>();
    PullRequestPushes pullRequests = (git, name, head, options) -> {
        prPushes.add(new PrPush(name, head, options));
        return List.of("[agenthub] Created PR #7 -> main (rev 1)", "  Status:  GET /v1/repos/alice/proj/pulls/7");
    };

    void run(RefPolicyHook hook, ReceiveCommand... commands) {
        run(hook, false, commands);
    }

    void run(RefPolicyHook hook, boolean atomic, ReceiveCommand... commands) {
        hook.check(db, atomic, List.of(), List.of(commands), messages::add);
    }

    static ReceiveCommand create(ObjectId id, String ref) {
        return new ReceiveCommand(ZERO, id, ref);
    }

    static ReceiveCommand update(ObjectId from, ObjectId to, String ref) {
        return new ReceiveCommand(from, to, ref);
    }

    static ReceiveCommand delete(ObjectId from, String ref) {
        return new ReceiveCommand(from, ZERO, ref);
    }

    static void assertRejected(ReceiveCommand command, String code) {
        assertThat(command.getResult()).isEqualTo(REJECTED_OTHER_REASON);
        assertThat(command.getMessage()).isEqualTo(code);
    }

    String output() {
        return String.join("\n", messages);
    }

    @Test
    void writerCreatesAndFastForwardsUnprotectedBranches() {
        ReceiveCommand fresh = create(next, "refs/heads/topic");
        ReceiveCommand forward = update(base, next, "refs/heads/feature");

        run(hook(Role.WRITE), fresh, forward);

        assertThat(fresh.getResult()).isEqualTo(NOT_ATTEMPTED);
        assertThat(forward.getResult()).isEqualTo(NOT_ATTEMPTED);
        assertThat(messages).isEmpty();
    }

    @Test
    void unprotectedBranchesAllowForcePushAndDelete() throws Exception {
        repo.update("refs/heads/feature", next);
        ReceiveCommand rewind = update(next, sideline, "refs/heads/feature");
        ReceiveCommand drop = delete(base, "refs/heads/old");
        repo.update("refs/heads/old", base);

        run(hook(Role.WRITE), rewind, drop);

        assertThat(rewind.getResult()).isEqualTo(NOT_ATTEMPTED);
        assertThat(drop.getResult()).isEqualTo(NOT_ATTEMPTED);
    }

    @Test
    void defaultBranchRequiresPullRequestAndHintsTheCanonicalPush() {
        ReceiveCommand push = update(base, next, "refs/heads/main");

        run(hook(Role.ADMIN), push);

        assertRejected(push, "protected_branch");
        assertThat(output()).contains("refs/heads/main").contains("protected_branch")
                .contains("git push origin " + next.name() + ":refs/heads/pr/bob-" + next.abbreviate(7).name());
    }

    @Test
    void defaultBranchCannotBeRewoundOrDeleted() {
        ReceiveCommand rewind = update(base, sideline, "refs/heads/main");
        ReceiveCommand drop = delete(base, "refs/heads/main");

        run(hook(Role.ADMIN), rewind);
        run(hook(Role.ADMIN), drop);

        assertRejected(rewind, "protected_branch");
        assertRejected(drop, "protected_branch");
        assertThat(output()).contains("cannot be deleted");
    }

    @Test
    void firstPushMayCreateTheMissingDefaultBranch() throws Exception {
        InMemoryRepository empty = new InMemoryRepository(new DfsRepositoryDescription("empty"));
        RevCommit first = new TestRepository<>(empty).commit().add("a", "1").create();
        ReceiveCommand create = create(first, "refs/heads/main");

        hook(Role.WRITE).check(empty, false, List.of(), List.of(create), messages::add);

        assertThat(create.getResult()).isEqualTo(NOT_ATTEMPTED);
    }

    @Test
    void proposerCannotPushBranchesAndIsPointedAtPullRequests() {
        ReceiveCommand push = create(next, "refs/heads/fix-parser");

        run(hook(Role.PROPOSE), push);

        assertRejected(push, "insufficient_role");
        assertThat(output()).contains("git push origin " + next.name() + ":refs/heads/pr/fix-parser");
    }

    @Test
    void proposerCannotPushTags() {
        ReceiveCommand tag = create(base, "refs/tags/v1");

        run(hook(Role.PROPOSE), tag);

        assertRejected(tag, "insufficient_role");
    }

    @Test
    void writerPushesTagsWithValidNames() throws Exception {
        ReceiveCommand lightweight = create(base, "refs/tags/v1.0");
        ReceiveCommand annotated = create(repo.tag("v2", base), "refs/tags/v2");
        ReceiveCommand badName = create(base, "refs/tags/V3");

        run(hook(Role.WRITE), lightweight, annotated, badName);

        assertThat(lightweight.getResult()).isEqualTo(NOT_ATTEMPTED);
        assertThat(annotated.getResult()).isEqualTo(NOT_ATTEMPTED);
        assertRejected(badName, "invalid_ref_name");
    }

    @Test
    void branchNamesFollowThePolicy() {
        ReceiveCommand upper = create(base, "refs/heads/Feature");
        ReceiveCommand windows = create(base, "refs/heads/docs/con");

        run(hook(Role.WRITE), upper, windows);

        assertRejected(upper, "invalid_ref_name");
        assertRejected(windows, "invalid_ref_name");
    }

    @Test
    void directoryFileConflictsAreExplained() {
        ReceiveCommand nested = create(base, "refs/heads/feature/x");
        ReceiveCommand parent = create(base, "refs/heads/topic");
        ReceiveCommand child = create(base, "refs/heads/topic/y");

        run(hook(Role.WRITE), nested, parent, child);

        assertRejected(nested, "invalid_ref_name");
        assertThat(output()).contains("refs/heads/feature");
        assertThat(parent.getResult()).isEqualTo(NOT_ATTEMPTED);
        assertRejected(child, "invalid_ref_name");
    }

    @Test
    void branchHeadsMustBeCommits() throws Exception {
        RevBlob blob = repo.blob("not a commit");
        ReceiveCommand push = create(blob, "refs/heads/blob");

        run(hook(Role.WRITE), push);

        assertRejected(push, "not_a_commit");
    }

    @Test
    void pullRequestPushIsHandedToPullRequestsAndAcceptedByTheServer() {
        ReceiveCommand pr = create(next, "refs/heads/pr/fix-parser");

        hook(Role.PROPOSE).check(db, false, List.of("draft"), List.of(pr), messages::add);

        assertThat(pr.getResult()).isEqualTo(ReceiveCommand.Result.OK);
        assertThat(prPushes).containsExactly(new PrPush("fix-parser", next, List.of("draft")));
        assertThat(messages).containsExactly("[agenthub] Created PR #7 -> main (rev 1)",
                "  Status:  GET /v1/repos/alice/proj/pulls/7");
    }

    @Test
    void pullRequestRefusalBecomesTheRejectionCode() {
        pullRequests = (git, name, head, options) -> {
            throw new HintedException(ErrorCode.NO_NEW_COMMITS, "nothing new", "commit first");
        };
        ReceiveCommand pr = create(base, "refs/heads/pr/fix");

        run(hook(Role.WRITE), pr);

        assertRejected(pr, "no_new_commits");
        assertThat(output()).contains("refs/heads/pr/fix: no_new_commits - nothing new").contains("hint: commit first");
    }

    @Test
    void busyRepositoryAsksToRetry() {
        pullRequests = (git, name, head, options) -> {
            throw new HintedException(ErrorCode.BUSY, "repository busy", "retry in a few seconds");
        };
        ReceiveCommand pr = create(next, "refs/heads/pr/fix");

        run(hook(Role.PROPOSE), pr);

        assertRejected(pr, "busy");
        assertThat(output()).contains("repository busy, retry");
    }

    @Test
    void pullRequestNamesFollowThePolicyAndNeedPropose() {
        ReceiveCommand badName = create(next, "refs/heads/pr/Fix");
        ReceiveCommand bare = create(next, "refs/heads/pr");

        run(hook(Role.PROPOSE), badName, bare);
        run(hook(Role.READ), create(next, "refs/heads/pr/x"));

        assertRejected(badName, "invalid_ref_name");
        assertRejected(bare, "reserved_ref");
        assertThat(prPushes).isEmpty();
        assertThat(output()).contains("insufficient_role");
    }

    @Test
    void pullRequestBranchCannotBeDeletedByPush() {
        ReceiveCommand drop = delete(base, "refs/heads/pr/fix");

        run(hook(Role.PROPOSE), drop);

        assertRejected(drop, "reserved_ref");
        assertThat(output()).contains("PATCH /v1/repos/alice/proj/pulls/N").contains("CLOSED");
    }

    @Test
    void gerritStyleRefsArePointedAtPrBranches() {
        ReceiveCommand gerrit = create(next, "refs/for/main");

        run(hook(Role.WRITE), gerrit);

        assertRejected(gerrit, "reserved_ref");
        assertThat(output()).contains("git push origin " + next.name() + ":refs/heads/pr/");
    }

    @Test
    void everythingElseIsReserved() {
        ReceiveCommand pull = create(next, "refs/pull/1/head");
        ReceiveCommand meta = create(next, "refs/meta/config");
        ReceiveCommand notes = create(next, "refs/notes/commits");

        run(hook(Role.ADMIN), pull, meta, notes);

        assertRejected(pull, "reserved_ref");
        assertRejected(meta, "reserved_ref");
        assertRejected(notes, "reserved_ref");
    }

    @Test
    void tooManyCommandsRejectsTheWholePush() {
        ReceiveCommand[] commands = IntStream.range(0, 3)
                .mapToObj(i -> create(base, "refs/heads/b" + i)).toArray(ReceiveCommand[]::new);

        run(hook(Role.WRITE, "main", 2, 1000), commands);

        for (ReceiveCommand command : commands) {
            assertRejected(command, "too_many_commands");
        }
    }

    @Test
    void atomicPushMixingPullRequestsIsRejected() {
        ReceiveCommand pr = create(next, "refs/heads/pr/x");
        ReceiveCommand branch = create(next, "refs/heads/topic");

        run(hook(Role.WRITE), true, pr, branch);

        assertThat(prPushes).isEmpty();

        assertRejected(pr, "atomic_mixed_push");
        assertRejected(branch, "atomic_mixed_push");
    }

    @Test
    void branchAndTagCountIsLimited() {
        ReceiveCommand third = create(base, "refs/heads/third");
        ReceiveCommand fourth = create(base, "refs/heads/fourth");

        run(hook(Role.WRITE, "main", 50, 3), third, fourth);

        assertThat(third.getResult()).isEqualTo(NOT_ATTEMPTED);
        assertRejected(fourth, "quota_exceeded");
    }

    @Test
    void overQuotaRepositoryAcceptsOnlyDeletesSoItCanShrink() throws Exception {
        repo.update("refs/heads/old", base);
        ReceiveCommand drop = delete(base, "refs/heads/old");
        ReceiveCommand fresh = create(next, "refs/heads/topic");
        ReceiveCommand forward = update(base, next, "refs/heads/feature");

        run(hook(Role.WRITE, "main", 50, 1000, true), drop, fresh, forward);

        assertThat(drop.getResult()).isEqualTo(NOT_ATTEMPTED);
        assertRejected(fresh, "quota_exceeded");
        assertRejected(forward, "quota_exceeded");
        assertThat(output()).contains("git push origin :refs/heads/<branch>");
    }

    @Test
    void messagesAreAscii() {
        ReceiveCommand weird = create(base, "refs/heads/café");

        run(hook(Role.WRITE), weird);

        assertRejected(weird, "invalid_ref_name");
        assertThat(output()).matches("\\A[\\x20-\\x7e\\n]*\\z");
    }

    @Test
    void unexpectedFailureRejectsOnlyThatCommand() {
        ReceiveCommand broken = create(ObjectId.fromString("1111111111111111111111111111111111111111"),
                "refs/heads/missing-object");
        ReceiveCommand fine = create(next, "refs/heads/topic");

        run(hook(Role.WRITE), broken, fine);

        assertRejected(broken, "internal error, retry");
        assertThat(fine.getResult()).isEqualTo(NOT_ATTEMPTED);
    }
}
