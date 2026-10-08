package org.example.agenthub.git;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.example.agenthub.events.DomainEvent;
import org.example.agenthub.maintenance.GcScheduler;
import org.example.agenthub.pr.Provenance;
import org.example.agenthub.pr.Reconciler;
import org.example.agenthub.pr.TargetMoved;
import org.example.agenthub.repo.RepoService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Post-receive work of §6.7: records a {@code push} event, refreshes the repository size, counts the push for GC
 * and reconciles PRs whose head a pushed branch now contains (§7.9).
 * Only queues work, because the client's HTTP response is not finished while the hook runs.
 */
@Component
public class PostReceiveHandler {

    private final ApplicationEventPublisher events;
    private final RepoService repos;
    private final GcScheduler gc;
    private final Reconciler reconciler;
    private final Executor executor;

    public PostReceiveHandler(ApplicationEventPublisher events, RepoService repos, GcScheduler gc,
            Reconciler reconciler, Executor executor) {
        this.events = events;
        this.repos = repos;
        this.gc = gc;
        this.reconciler = reconciler;
        this.executor = executor;
    }

    public PostReceiveHook hookFor(GitRequestContext context) {
        Provenance pusher = Provenance.of(context.principal(), context.remoteAddress(), context.userAgent());
        return (receivePack, commands) -> updated(context.repoId(), pusher,
                commands.stream().filter(command -> isRealRef(command.getRefName())).toList());
    }

    /** The same work for refs the server moved on an agent's behalf (content API, §10.5). */
    public void updated(long repoId, Provenance by, List<ReceiveCommand> commands) {
        if (commands.isEmpty()) {
            return;
        }
        List<Map<String, String>> refs = commands.stream().map(PostReceiveHandler::describe).toList();
        events.publishEvent(new DomainEvent(DomainEvent.PUSH, repoId, null, by.agent(), payload(by, refs)));
        executor.execute(() -> repos.refreshSize(repoId));
        gc.pushed(repoId);
        for (ReceiveCommand command : commands) {
            String ref = command.getRefName();
            if (ref.startsWith(Constants.R_HEADS) && command.getType() != ReceiveCommand.Type.DELETE) {
                String branch = ref.substring(Constants.R_HEADS.length());
                executor.execute(() -> reconciler.afterPush(repoId, branch, command.getNewId(), by.agent()));
                events.publishEvent(new TargetMoved(repoId, branch));
            }
        }
    }

    /** Virtual {@code pr/*} commands also arrive here as OK; they are not ref updates. */
    static boolean isRealRef(String ref) {
        return ref.startsWith(Constants.R_TAGS)
                || ref.startsWith(Constants.R_HEADS) && !ref.equals(RefPolicyHook.PULL_REQUEST_BRANCH)
                        && !ref.startsWith(RefPolicyHook.PULL_REQUEST_PREFIX);
    }

    private static Map<String, String> describe(ReceiveCommand command) {
        return Map.of("ref", command.getRefName(), "before", command.getOldId().name(),
                "after", command.getNewId().name(), "type", command.getType().name());
    }

    private static Map<String, Object> payload(Provenance by, List<Map<String, String>> refs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refs", refs);
        payload.put("model", by.model());
        payload.put("session", by.session());
        payload.put("operator", by.operator());
        payload.put("ip", by.ip());
        payload.put("userAgent", by.userAgent());
        return payload;
    }
}
