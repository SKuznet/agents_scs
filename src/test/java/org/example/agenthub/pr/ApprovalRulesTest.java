package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/** Invariant D8 (§7.7) and the "latest verdict per reviewer" rule (§7.6) as pure functions. */
class ApprovalRulesTest {

    static final Predicate<String> WRITERS = Set.of("alice", "carol", "dave")::contains;

    static ApprovalRules.Vote vote(String reviewer, Verdict verdict) {
        return new ApprovalRules.Vote(reviewer, verdict);
    }

    @Test
    void authorWithoutWriteNeedsOneApprovalEvenWhenBranchRequiresNone() {
        assertThat(ApprovalRules.evaluate("bob", false, 0, List.of(), WRITERS).required()).isEqualTo(1);
        assertThat(ApprovalRules.evaluate("bob", false, 2, List.of(), WRITERS).required()).isEqualTo(2);
    }

    @Test
    void authorWithWriteNeedsWhatTheBranchRequires() {
        assertThat(ApprovalRules.evaluate("alice", true, 0, List.of(), WRITERS).required()).isZero();
        assertThat(ApprovalRules.evaluate("alice", true, 0, List.of(), WRITERS).satisfied()).isTrue();
        assertThat(ApprovalRules.evaluate("alice", true, 1, List.of(), WRITERS).satisfied()).isFalse();
    }

    @Test
    void onlyApprovalsFromCurrentWritersOtherThanTheAuthorCount() {
        var status = ApprovalRules.evaluate("carol", true, 1, List.of(
                vote("carol", Verdict.APPROVE),
                vote("eve", Verdict.APPROVE),
                vote("alice", Verdict.APPROVE)), WRITERS);

        assertThat(status.approvals()).containsExactly("alice");
        assertThat(status.satisfied()).isTrue();
    }

    @Test
    void approvalFromAgentThatLostWriteDoesNotCount() {
        var status = ApprovalRules.evaluate("bob", false, 1, List.of(vote("frank", Verdict.APPROVE)), WRITERS);

        assertThat(status.approvals()).isEmpty();
        assertThat(status.satisfied()).isFalse();
    }

    @Test
    void latestVerdictOfEachReviewerWinsAndCommentsDoNotOverrideIt() {
        var status = ApprovalRules.evaluate("bob", false, 1, List.of(
                vote("alice", Verdict.REQUEST_CHANGES),
                vote("alice", Verdict.APPROVE),
                vote("alice", Verdict.COMMENT),
                vote("carol", Verdict.APPROVE),
                vote("carol", Verdict.REQUEST_CHANGES)), WRITERS);

        assertThat(status.approvals()).containsExactly("alice");
        assertThat(status.blockers()).containsExactly("carol");
        assertThat(status.satisfied()).isFalse();
    }

    @Test
    void repeatedApprovalCountsOnce() {
        var status = ApprovalRules.evaluate("bob", false, 2, List.of(
                vote("alice", Verdict.APPROVE), vote("alice", Verdict.APPROVE)), WRITERS);

        assertThat(status.approvals()).containsExactly("alice");
        assertThat(status.satisfied()).isFalse();
    }

    @Test
    void blockFromAgentThatLostWriteNoLongerBlocks() {
        var status = ApprovalRules.evaluate("bob", false, 1, List.of(
                vote("alice", Verdict.APPROVE), vote("frank", Verdict.REQUEST_CHANGES)), WRITERS);

        assertThat(status.blockers()).isEmpty();
        assertThat(status.satisfied()).isTrue();
    }
}
