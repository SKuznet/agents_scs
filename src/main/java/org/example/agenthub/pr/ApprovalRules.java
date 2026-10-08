package org.example.agenthub.pr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Invariant D8 (§7.7) over the reviews of the current revision (§7.6): the latest APPROVE/REQUEST_CHANGES of
 * each reviewer counts (a later COMMENT does not withdraw it), and only reviewers who hold WRITE at the moment
 * of evaluation count, both for approving and for blocking.
 */
public final class ApprovalRules {

    private ApprovalRules() {
    }

    public record Vote(String reviewer, Verdict verdict) {
    }

    public record Status(List<String> approvals, int required, List<String> blockers) {
        public boolean satisfied() {
            return approvals.size() >= required && blockers.isEmpty();
        }

        public int missing() {
            return Math.max(0, required - approvals.size());
        }
    }

    /** Approvals the branch alone would not require are still needed from an author without WRITE (D8). */
    public static int required(boolean authorCanWrite, int requiredApprovals) {
        return authorCanWrite ? requiredApprovals : Math.max(1, requiredApprovals);
    }

    /** {@code votes} in the order they were cast. */
    public static Status evaluate(String author, boolean authorCanWrite, int requiredApprovals, List<Vote> votes,
            Predicate<String> canWrite) {
        Map<String, Verdict> latest = new LinkedHashMap<>();
        for (Vote vote : votes) {
            if (vote.verdict() != Verdict.COMMENT) {
                latest.remove(vote.reviewer());
                latest.put(vote.reviewer(), vote.verdict());
            }
        }
        List<String> approvals = reviewersWith(latest, Verdict.APPROVE, author, canWrite);
        List<String> blockers = reviewersWith(latest, Verdict.REQUEST_CHANGES, author, canWrite);
        return new Status(approvals, required(authorCanWrite, requiredApprovals), blockers);
    }

    private static List<String> reviewersWith(Map<String, Verdict> latest, Verdict verdict, String author,
            Predicate<String> canWrite) {
        return latest.entrySet().stream()
                .filter(e -> e.getValue() == verdict && !e.getKey().equals(author) && canWrite.test(e.getKey()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
