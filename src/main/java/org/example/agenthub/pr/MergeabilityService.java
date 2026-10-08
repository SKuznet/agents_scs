package org.example.agenthub.pr;

import java.io.IOException;
import java.util.List;
import java.util.TreeSet;

import org.eclipse.jgit.errors.NoMergeBaseException;
import org.eclipse.jgit.internal.storage.file.ObjectDirectory;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.springframework.stereotype.Component;

/** In-core merge of a PR head into the tip of its target (§7.4). */
@Component
public class MergeabilityService {

    public record Result(Mergeable status, ObjectId tree, List<String> conflictPaths, String error) {
    }

    /** A trial merge: its objects go to a pack inserter that is never flushed, so nothing reaches the repo. */
    public Result check(Repository db, AnyObjectId tip, AnyObjectId head) throws IOException {
        try (ObjectInserter scratch = scratchInserter(db)) {
            return merge(db, scratch, tip, head);
        }
    }

    /** Merges with the caller's inserter; the caller decides whether to flush (a real merge) or not. */
    public static Result merge(Repository db, ObjectInserter inserter, AnyObjectId tip, AnyObjectId head)
            throws IOException {
        if (mergeBase(db, tip, head) == null) {
            return new Result(Mergeable.ERROR, null, List.of(), "no merge base: the histories are unrelated");
        }
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RECURSIVE.newMerger(inserter, db.getConfig());
        try {
            if (merger.merge(false, tip, head)) {
                return new Result(Mergeable.CLEAN, merger.getResultTreeId(), List.of(), null);
            }
            TreeSet<String> paths = new TreeSet<>(merger.getUnmergedPaths());
            if (merger.getFailingPaths() != null) {
                paths.addAll(merger.getFailingPaths().keySet());
            }
            return new Result(Mergeable.CONFLICT, null, List.copyOf(paths), null);
        } catch (NoMergeBaseException e) {
            return new Result(Mergeable.ERROR, null, List.of(), e.getMessage());
        }
    }

    public static ObjectId mergeBase(Repository db, AnyObjectId a, AnyObjectId b) throws IOException {
        try (RevWalk walk = new RevWalk(db)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(a));
            walk.markStart(walk.parseCommit(b));
            var base = walk.next();
            return base == null ? null : base.copy();
        }
    }

    static ObjectInserter scratchInserter(Repository db) {
        return db.getObjectDatabase() instanceof ObjectDirectory directory
                ? directory.newPackInserter()
                : db.newObjectInserter();
    }
}
