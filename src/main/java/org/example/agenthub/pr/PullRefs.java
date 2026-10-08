package org.example.agenthub.pr;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Component;

/** Server-owned refs of a PR (D5): {@code refs/pull/N/head}, {@code refs/pull/N/rev/K}; derived from the DB (§13). */
@Component
public class PullRefs {

    public static String head(int number) {
        return "refs/pull/" + number + "/head";
    }

    public static String revision(int number, int rev) {
        return "refs/pull/" + number + "/rev/" + rev;
    }

    public void write(Repository db, int number, int rev, ObjectId sha, PersonIdent who) throws IOException {
        set(db, revision(number, rev), sha, who);
        set(db, head(number), sha, who);
    }

    /**
     * Makes the refs of PR {@code number} match the database: used after a failed transaction (§13, best effort)
     * and at startup. {@code pr} is null when the PR does not exist (its creation was rolled back).
     */
    public void restore(Repository db, int number, PullRequest pr, List<PrRevision> revisions, PersonIdent who)
            throws IOException {
        Map<String, PrRevision> expected = revisions.stream()
                .collect(Collectors.toMap(r -> revision(number, r.getRev()), Function.identity()));
        for (Ref ref : db.getRefDatabase().getRefsByPrefix("refs/pull/" + number + "/")) {
            boolean wanted = ref.getName().equals(head(number)) ? pr != null : expected.containsKey(ref.getName());
            if (!wanted) {
                delete(db, ref.getName(), who);
            }
        }
        for (PrRevision revision : revisions) {
            set(db, revision(number, revision.getRev()), ObjectId.fromString(revision.getHeadSha()), who);
        }
        if (pr != null) {
            set(db, head(number), ObjectId.fromString(pr.getHeadSha()), who);
        }
    }

    private static void set(Repository db, String name, ObjectId sha, PersonIdent who) throws IOException {
        Ref current = db.exactRef(name);
        if (current != null && sha.equals(current.getObjectId())) {
            return;
        }
        RefUpdate update = db.updateRef(name);
        update.setNewObjectId(sha);
        update.setForceUpdate(true);
        update.setRefLogIdent(who);
        update.setRefLogMessage("agenthub pull request", false);
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED
                && result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("cannot update " + name + ": " + result);
        }
    }

    private static void delete(Repository db, String name, PersonIdent who) throws IOException {
        RefUpdate update = db.updateRef(name);
        update.setForceUpdate(true);
        update.setRefLogIdent(who);
        RefUpdate.Result result = update.delete();
        if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("cannot delete " + name + ": " + result);
        }
    }
}
