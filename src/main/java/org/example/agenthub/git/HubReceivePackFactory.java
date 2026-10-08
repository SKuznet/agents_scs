package org.example.agenthub.git;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.example.agenthub.access.Role;
import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.pr.Provenance;
import org.example.agenthub.pr.PullRequestService;
import org.example.agenthub.repo.BranchProtection;
import org.example.agenthub.repo.BranchProtectionRepository;
import org.example.agenthub.repo.Identities;
import org.springframework.stereotype.Component;

/** Receive-pack configured as §6.5 prescribes. */
@Component
public class HubReceivePackFactory implements ReceivePackFactory<HttpServletRequest> {

    /** An over-quota repository accepts deletions only, which need no pack data (§6.5). */
    static final long OVER_QUOTA_PACK_LIMIT = 1024;

    private final AgentHubProperties.Limits limits;
    private final Identities identities;
    private final BranchProtectionRepository protections;
    private final PostReceiveHandler postReceive;
    private final PullRequestService pullRequests;

    public HubReceivePackFactory(AgentHubProperties properties, Identities identities,
            BranchProtectionRepository protections, PostReceiveHandler postReceive,
            PullRequestService pullRequests) {
        this.limits = properties.limits();
        this.identities = identities;
        this.protections = protections;
        this.postReceive = postReceive;
        this.pullRequests = pullRequests;
    }

    @Override
    public ReceivePack create(HttpServletRequest request, Repository db) throws ServiceNotAuthorizedException {
        GitRequestContext context = GitRequestContext.of(request);
        if (context == null || !context.role().atLeast(Role.PROPOSE)) {
            throw new ServiceNotAuthorizedException();
        }
        ReceivePack pack = new ReceivePack(db);
        pack.setAllowPushOptions(true);
        pack.setCheckReceivedObjects(true);
        pack.setMaxPackSizeLimit(context.overQuota() ? OVER_QUOTA_PACK_LIMIT : limits.maxPackSize().toBytes());
        pack.setMaxObjectSizeLimit(limits.maxObjectSize().toBytes());
        pack.setRefLogIdent(identities.agent(context.agent()));
        pack.setPreReceiveHook(new RefPolicyHook(new RefPolicyHook.Settings(context.agent(), context.role(),
                context.fullName(), context.defaultBranch(), protectionsOf(context.repoId()),
                limits.maxCommandsPerPush(), limits.maxRefsPerRepo(), context.overQuota(),
                pullRequestsOf(context))));
        pack.setPostReceiveHook(postReceive.hookFor(context));
        return pack;
    }

    private PullRequestPushes pullRequestsOf(GitRequestContext context) {
        Provenance pusher = Provenance.of(context.principal(), context.remoteAddress(), context.userAgent());
        return (db, name, head, options) -> pullRequests.openOrUpdate(db, context.repoId(), context.role(), pusher,
                name, head, options);
    }

    private Map<String, BranchProtection> protectionsOf(long repoId) {
        return protections.findByRepoId(repoId).stream()
                .collect(Collectors.toMap(BranchProtection::getBranch, Function.identity()));
    }
}
