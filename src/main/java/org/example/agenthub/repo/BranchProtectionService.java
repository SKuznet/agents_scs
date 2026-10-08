package org.example.agenthub.repo;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.example.agenthub.access.Role;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.events.StateChanged;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Branch protection API of §6.4 and §10.2; a branch without a record is unprotected. */
@Service
public class BranchProtectionService {

    static final int MAX_REQUIRED_APPROVALS = 10;

    private final BranchProtectionRepository protections;
    private final RepoAccessService access;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public BranchProtectionService(BranchProtectionRepository protections, RepoAccessService access,
            TransactionTemplate tx, ApplicationEventPublisher events) {
        this.protections = protections;
        this.access = access;
        this.tx = tx;
        this.events = events;
    }

    public record Settings(Boolean requirePr, Boolean allowForcePush, Boolean allowDelete, Integer requiredApprovals,
            Boolean autoMerge, MergeStrategy autoMergeStrategy) {
    }

    public record ProtectionView(String branch, boolean requirePr, boolean allowForcePush, boolean allowDelete,
            int requiredApprovals, boolean autoMerge, MergeStrategy autoMergeStrategy,
            @JsonProperty("default") boolean defaultBranch, boolean configured) {

        static ProtectionView of(Repo repo, BranchProtection p, boolean configured) {
            return new ProtectionView(p.getBranch(), p.isRequirePr(), p.isAllowForcePush(), p.isAllowDelete(),
                    p.getRequiredApprovals(), p.isAutoMerge(), p.getAutoMergeStrategy(),
                    p.getBranch().equals(repo.getDefaultBranch()), configured);
        }
    }

    /** Effective settings: the record, or the unprotected defaults of §6.4. */
    public static BranchProtection effective(BranchProtectionRepository protections, long repoId, String branch) {
        return protections.findById(new BranchProtection.Key(repoId, branch))
                .orElseGet(() -> BranchProtection.unprotected(repoId, branch));
    }

    public ProtectionView get(String agent, String owner, String name, String branch) {
        Repo repo = access.require(agent, owner, name, Role.READ).repo();
        checkBranch(branch);
        var record = protections.findById(new BranchProtection.Key(repo.getId(), branch));
        return ProtectionView.of(repo, record.orElseGet(() -> BranchProtection.unprotected(repo.getId(), branch)),
                record.isPresent());
    }

    public ProtectionView replace(String agent, String owner, String name, String branch, Settings settings) {
        Repo repo = access.require(agent, owner, name, Role.ADMIN).repo();
        checkBranch(branch);
        BranchProtection wanted = validate(repo, branch, settings);
        BranchProtection saved = tx.execute(status -> {
            events.publishEvent(new StateChanged(repo.getId()));
            var existing = protections.findById(new BranchProtection.Key(repo.getId(), branch));
            if (existing.isEmpty()) {
                return protections.save(wanted);
            }
            existing.get().replaceWith(wanted);
            return existing.get();
        });
        return ProtectionView.of(repo, saved, true);
    }

    public void remove(String agent, String owner, String name, String branch) {
        Repo repo = access.require(agent, owner, name, Role.ADMIN).repo();
        checkBranch(branch);
        if (branch.equals(repo.getDefaultBranch())) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "'" + branch + "' is the default branch of "
                    + repo.getFullName() + " and stays protected",
                    "change its settings with PUT, or make another branch the default first");
        }
        tx.executeWithoutResult(status -> {
            protections.findById(new BranchProtection.Key(repo.getId(), branch)).ifPresent(protections::delete);
            events.publishEvent(new StateChanged(repo.getId()));
        });
    }

    private static BranchProtection validate(Repo repo, String branch, Settings s) {
        if (s.requirePr() == null || s.allowForcePush() == null || s.allowDelete() == null
                || s.requiredApprovals() == null || s.autoMerge() == null) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "PUT replaces the whole protection: requirePr,"
                    + " allowForcePush, allowDelete, requiredApprovals and autoMerge are required",
                    "GET the current settings, change what you need and PUT them back");
        }
        if (s.requiredApprovals() < 0 || s.requiredApprovals() > MAX_REQUIRED_APPROVALS) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED,
                    "requiredApprovals must be 0-" + MAX_REQUIRED_APPROVALS, "pick a smaller number");
        }
        if (s.allowDelete() && branch.equals(repo.getDefaultBranch())) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "the default branch can never be deleted",
                    "send allowDelete=false");
        }
        MergeStrategy strategy = s.autoMergeStrategy() == null ? MergeStrategy.SQUASH : s.autoMergeStrategy();
        return BranchProtection.configured(repo.getId(), branch, s.requirePr(), s.allowForcePush(), s.allowDelete(),
                s.requiredApprovals(), s.autoMerge(), strategy);
    }

    private static void checkBranch(String branch) {
        String problem = NamePolicy.refNameProblem(branch);
        if (problem != null) {
            throw new HintedException(ErrorCode.INVALID_REF_NAME, "invalid branch name '" + branch + "': " + problem,
                    "pass the branch as ?branch=<name>, e.g. ?branch=feature/x");
        }
    }
}
