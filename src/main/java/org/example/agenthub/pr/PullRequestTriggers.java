package org.example.agenthub.pr;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Turns PR and branch changes into background work, once the change is committed. */
@Component
class PullRequestTriggers {

    private final PullRequestTasks tasks;
    private final PullRequestRepository pulls;

    PullRequestTriggers(PullRequestTasks tasks, PullRequestRepository pulls) {
        this.tasks = tasks;
        this.pulls = pulls;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(PullRequestChanged change) {
        tasks.submit(change.repoId(), change.prId());
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(TargetMoved moved) {
        pulls.findByRepoIdAndStateAndTargetBranch(moved.repoId(), PrState.OPEN, moved.branch())
                .forEach(pr -> tasks.submit(moved.repoId(), pr.getId()));
    }
}
