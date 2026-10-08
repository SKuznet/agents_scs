package org.example.agenthub.git;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.example.agenthub.access.Role;
import org.example.agenthub.repo.BranchProtection;
import org.example.agenthub.repo.NamePolicy;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-receive policy of §6.6. Commands it leaves {@code NOT_ATTEMPTED} are applied by JGit; refusals carry the
 * error code as the rejection reason and the explanation plus a ready-to-run hint as {@code remote:} lines.
 * Hints are built from the command's sha, so they also work from a detached HEAD.
 */
public class RefPolicyHook implements PreReceiveHook {

    private static final Logger log = LoggerFactory.getLogger(RefPolicyHook.class);

    static final String PULL_REQUEST_BRANCH = Constants.R_HEADS + "pr";
    static final String PULL_REQUEST_PREFIX = PULL_REQUEST_BRANCH + "/";
    static final String GERRIT_PREFIX = "refs/for/";
    static final String INTERNAL_ERROR = "internal error, retry";

    /** Everything the policy needs about the pusher and the repository, captured when the push starts. */
    public record Settings(String agent, Role role, String repo, String defaultBranch,
            Map<String, BranchProtection> protections, int maxCommands, int maxRefs, boolean overQuota,
            PullRequestPushes pullRequests) {
    }

    private record Rejection(ErrorCode code, String reason, String hint) {
    }

    private final Settings settings;

    public RefPolicyHook(Settings settings) {
        this.settings = settings;
    }

    @Override
    public void onPreReceive(ReceivePack receivePack, Collection<ReceiveCommand> commands) {
        List<String> options = receivePack.getPushOptions();
        check(receivePack.getRepository(), receivePack.isAtomic(), options == null ? List.of() : options, commands,
                receivePack::sendMessage);
    }

    void check(Repository db, boolean atomic, List<String> pushOptions, Collection<ReceiveCommand> commands,
            Consumer<String> out) {
        if (commands.size() > settings.maxCommands()) {
            rejectAll(commands, out, new Rejection(ErrorCode.TOO_MANY_COMMANDS,
                    "this push updates " + commands.size() + " refs; at most " + settings.maxCommands()
                            + " are accepted per push",
                    "push in smaller batches"));
            return;
        }
        if (atomic && commands.size() > 1
                && commands.stream().anyMatch(c -> isPullRequestRef(c.getRefName()))) {
            rejectAll(commands, out, new Rejection(ErrorCode.ATOMIC_MIXED_PUSH,
                    "an atomic push cannot combine pull request refs (pr/*) with other refs",
                    "push each pr/<name> on its own, without --atomic"));
            return;
        }
        PushState state = new PushState(db, pushOptions, out);
        for (ReceiveCommand command : commands) {
            try {
                Rejection rejection = evaluate(db, command, state);
                if (rejection != null) {
                    reject(command, out, rejection);
                } else if (command.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
                    state.accepted(command);
                }
            } catch (Exception e) {
                log.error("pre-receive check of {} in {} failed", command.getRefName(), settings.repo(), e);
                command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, INTERNAL_ERROR);
            }
        }
    }

    private Rejection evaluate(Repository db, ReceiveCommand command, PushState state) throws IOException {
        String ref = command.getRefName();
        if (settings.overQuota() && !isDelete(command)) {
            // §6.5: an over-quota repository may only shrink: deletes now, space comes back with the next GC
            return new Rejection(ErrorCode.QUOTA_EXCEEDED,
                    settings.repo() + " is over its size quota; only deletions are accepted",
                    "delete branches or tags you no longer need: git push origin :refs/heads/<branch>");
        }
        if (ref.startsWith(Constants.R_HEADS) && !isDelete(command) && !isCommit(db, command.getNewId())) {
            return new Rejection(ErrorCode.NOT_A_COMMIT, "branches can only point at commits",
                    "push a commit, e.g. git push origin HEAD:" + ref);
        }
        if (ref.startsWith(PULL_REQUEST_PREFIX)) {
            return pullRequest(db, command, ref.substring(PULL_REQUEST_PREFIX.length()), state);
        }
        if (ref.startsWith(Constants.R_HEADS)) {
            return branch(db, command, ref.substring(Constants.R_HEADS.length()), state);
        }
        if (ref.startsWith(Constants.R_TAGS)) {
            return tag(db, command, ref.substring(Constants.R_TAGS.length()), state);
        }
        if (ref.startsWith(GERRIT_PREFIX) && !isDelete(command)) {
            String target = ref.substring(GERRIT_PREFIX.length());
            String option = target.equals(settings.defaultBranch()) ? "" : " -o target=" + target;
            return new Rejection(ErrorCode.RESERVED_REF, "refs/for/* is not used by AgentHub",
                    "propose changes as a pull request: " + canonicalPush(command.getNewId(), null) + option);
        }
        return new Rejection(ErrorCode.RESERVED_REF, "only branches and tags can be pushed",
                "push branches to refs/heads/<name> and tags to refs/tags/<name>");
    }

    private Rejection pullRequest(Repository db, ReceiveCommand command, String name, PushState state) {
        if (!settings.role().atLeast(Role.PROPOSE)) {
            return insufficientRole(Role.PROPOSE, "proposing changes");
        }
        if (isDelete(command)) {
            return new Rejection(ErrorCode.RESERVED_REF,
                    "pull request branches are virtual and cannot be deleted by a push",
                    "close the pull request instead: PATCH /v1/repos/" + settings.repo()
                            + "/pulls/N {\"state\":\"CLOSED\"}");
        }
        String problem = NamePolicy.refNameProblem(name);
        if (problem != null) {
            return new Rejection(ErrorCode.INVALID_REF_NAME, "invalid pull request name: " + problem,
                    canonicalPush(command.getNewId(), null));
        }
        try {
            List<String> lines = settings.pullRequests().push(db, name, command.getNewId(), state.pushOptions);
            // the ref is virtual (D7): OK tells the client it worked, and JGit applies only NOT_ATTEMPTED commands
            command.setResult(ReceiveCommand.Result.OK);
            lines.forEach(line -> state.out.accept(ascii(line)));
            return null;
        } catch (HintedException e) {
            return e.code() == ErrorCode.BUSY
                    ? new Rejection(ErrorCode.BUSY, "repository busy, retry", null)
                    : new Rejection(e.code(), e.getMessage(), e.hint());
        }
    }

    private Rejection branch(Repository db, ReceiveCommand command, String name, PushState state) throws IOException {
        if (name.equals("pr")) {
            return new Rejection(ErrorCode.RESERVED_REF, "'pr' is reserved for pull requests",
                    isDelete(command) ? null : canonicalPush(command.getNewId(), null));
        }
        if (!settings.role().atLeast(Role.WRITE)) {
            return isDelete(command)
                    ? insufficientRole(Role.WRITE, "deleting branches")
                    : new Rejection(ErrorCode.INSUFFICIENT_ROLE,
                            "pushing branches needs WRITE; you have " + settings.role(),
                            "propose the change as a pull request: " + canonicalPush(command.getNewId(), name));
        }
        Rejection invalid = checkNewRef(db, command, name, state);
        if (invalid != null) {
            return invalid;
        }

        boolean isDefault = name.equals(settings.defaultBranch());
        BranchProtection protection = settings.protections().getOrDefault(name,
                BranchProtection.unprotected(null, name));
        if (isDelete(command)) {
            if (isDefault) {
                return new Rejection(ErrorCode.PROTECTED_BRANCH, "the default branch '" + name + "' cannot be deleted",
                        "make another branch the default first: PATCH /v1/repos/" + settings.repo()
                                + " {\"defaultBranch\":\"<branch>\"}");
            }
            return protection.isAllowDelete() ? null
                    : new Rejection(ErrorCode.PROTECTED_BRANCH, "branch '" + name + "' is protected against deletion",
                            null);
        }
        // §6.4: the very first push into an empty repository may create the default branch directly
        boolean createsMissingDefault = isDefault && command.getType() == ReceiveCommand.Type.CREATE
                && db.exactRef(command.getRefName()) == null;
        if (protection.isRequirePr() && !createsMissingDefault) {
            return new Rejection(ErrorCode.PROTECTED_BRANCH,
                    "direct pushes to '" + name + "' are not allowed; changes land through pull requests",
                    "propose the change as a pull request: " + canonicalPush(command.getNewId(), name));
        }
        if (command.getType() != ReceiveCommand.Type.CREATE && !protection.isAllowForcePush()
                && !isFastForward(db, command.getOldId(), command.getNewId())) {
            return new Rejection(ErrorCode.PROTECTED_BRANCH,
                    "non-fast-forward updates of '" + name + "' are not allowed",
                    "git fetch origin && git rebase origin/" + name + ", then push again");
        }
        return null;
    }

    private Rejection tag(Repository db, ReceiveCommand command, String name, PushState state) throws IOException {
        if (!settings.role().atLeast(Role.WRITE)) {
            return insufficientRole(Role.WRITE, "pushing tags");
        }
        return checkNewRef(db, command, name, state);
    }

    /** Name policy, D/F conflicts and the ref count limit for refs that come into existence. */
    private Rejection checkNewRef(Repository db, ReceiveCommand command, String name, PushState state)
            throws IOException {
        if (isDelete(command)) {
            return null;
        }
        String problem = NamePolicy.refNameProblem(name);
        if (problem != null) {
            return new Rejection(ErrorCode.INVALID_REF_NAME, "invalid name '" + name + "': " + problem,
                    "pick a name like feature/fix-parser or v1.2.0");
        }
        if (command.getType() != ReceiveCommand.Type.CREATE) {
            return null;
        }
        String conflict = state.conflictWith(command.getRefName());
        if (conflict != null) {
            return new Rejection(ErrorCode.INVALID_REF_NAME,
                    "'" + command.getRefName() + "' conflicts with existing '" + conflict
                            + "' (a ref cannot be both a file and a directory)",
                    "choose another name, or delete " + conflict + " first");
        }
        if (state.refCount() + 1 > settings.maxRefs()) {
            return new Rejection(ErrorCode.QUOTA_EXCEEDED,
                    "the repository already has " + state.refCount() + " branches and tags; the limit is "
                            + settings.maxRefs(),
                    "delete refs you no longer need: git push origin :refs/heads/<name>");
        }
        return null;
    }

    private Rejection insufficientRole(Role needed, String what) {
        String owner = settings.repo().substring(0, settings.repo().indexOf('/'));
        return new Rejection(ErrorCode.INSUFFICIENT_ROLE,
                what + " needs " + needed + "; you have " + settings.role(),
                "ask the owner '" + owner + "' (or an ADMIN) to run: PUT /v1/repos/" + settings.repo() + "/grants/"
                        + settings.agent() + " {\"role\":\"" + needed + "\"}");
    }

    private String canonicalPush(ObjectId sha, String branch) {
        boolean reusable = branch != null && !branch.equals(settings.defaultBranch())
                && NamePolicy.refNameProblem(branch) == null;
        String name = reusable ? branch : settings.agent() + "-" + sha.abbreviate(7).name();
        return "git push origin " + sha.name() + ":" + PULL_REQUEST_PREFIX + name;
    }

    private void rejectAll(Collection<ReceiveCommand> commands, Consumer<String> out, Rejection rejection) {
        for (ReceiveCommand command : commands) {
            command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, rejection.code().code());
        }
        say(out, rejection.code().code() + " - " + rejection.reason());
        if (rejection.hint() != null) {
            say(out, "  hint: " + rejection.hint());
        }
    }

    private void reject(ReceiveCommand command, Consumer<String> out, Rejection rejection) {
        command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, rejection.code().code());
        say(out, command.getRefName() + ": " + rejection.code().code() + " - " + rejection.reason());
        if (rejection.hint() != null) {
            say(out, "  hint: " + rejection.hint());
        }
    }

    private static void say(Consumer<String> out, String message) {
        out.accept(ascii("[agenthub] " + message));
    }

    /** Messages are ASCII only (§7.2); anything else, e.g. from a rejected ref name, becomes '?'. */
    private static String ascii(String message) {
        StringBuilder ascii = new StringBuilder(message.length());
        message.chars().forEach(c -> ascii.append(c >= 0x20 && c < 0x7f ? (char) c : '?'));
        return ascii.toString();
    }

    private static boolean isPullRequestRef(String ref) {
        return ref.startsWith(PULL_REQUEST_PREFIX);
    }

    private static boolean isDelete(ReceiveCommand command) {
        return command.getType() == ReceiveCommand.Type.DELETE;
    }

    private static boolean isCommit(Repository db, ObjectId id) throws IOException {
        try (RevWalk walk = new RevWalk(db)) {
            return walk.parseAny(id) instanceof RevCommit;
        }
    }

    private static boolean isFastForward(Repository db, ObjectId oldId, ObjectId newId) throws IOException {
        try (RevWalk walk = new RevWalk(db)) {
            RevObject before = walk.parseAny(oldId);
            RevObject after = walk.parseAny(newId);
            return before instanceof RevCommit from && after instanceof RevCommit to && walk.isMergedInto(from, to);
        }
    }

    /** Refs created earlier in the same push count for D/F conflicts and the ref limit. */
    private static final class PushState {

        private final Repository db;
        private final List<String> pushOptions;
        private final Consumer<String> out;
        private final Set<String> created = new HashSet<>();
        private int refCount = -1;

        PushState(Repository db, List<String> pushOptions, Consumer<String> out) {
            this.db = db;
            this.pushOptions = pushOptions;
            this.out = out;
        }

        int refCount() throws IOException {
            if (refCount < 0) {
                refCount = db.getRefDatabase().getRefsByPrefix(Constants.R_HEADS, Constants.R_TAGS).size();
            }
            return refCount;
        }

        String conflictWith(String ref) throws IOException {
            Collection<String> existing = db.getRefDatabase().getConflictingNames(ref);
            if (!existing.isEmpty()) {
                return existing.iterator().next();
            }
            for (String other : created) {
                if (other.startsWith(ref + "/") || ref.startsWith(other + "/")) {
                    return other;
                }
            }
            return null;
        }

        void accepted(ReceiveCommand command) throws IOException {
            String ref = command.getRefName();
            if (command.getType() == ReceiveCommand.Type.CREATE
                    && (ref.startsWith(Constants.R_HEADS) || ref.startsWith(Constants.R_TAGS))) {
                refCount();
                refCount++;
                created.add(ref);
            }
        }
    }
}
