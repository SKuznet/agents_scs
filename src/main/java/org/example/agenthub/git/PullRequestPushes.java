package org.example.agenthub.git;

import java.util.List;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/** What the pre-receive hook does with {@code refs/heads/pr/<name>} (§6.6), bound to the pusher. */
@FunctionalInterface
public interface PullRequestPushes {

    /**
     * Opens or updates the pusher's pull request {@code name}.
     *
     * @return the ASCII lines to show as {@code remote:} messages
     * @throws org.example.agenthub.web.HintedException with the rejection code and hint
     */
    List<String> push(Repository db, String name, ObjectId head, List<String> pushOptions);
}
