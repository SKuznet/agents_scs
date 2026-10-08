package org.example.agenthub.repo;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.example.agenthub.config.AgentHubProperties;
import org.example.agenthub.config.JGitEnvironment;
import org.springframework.stereotype.Component;

/** Bare repositories on disk, laid out by database id: {@code {storage-root}/{id}.git} (D12). */
@Component
public class RepoStorage {

    private final Path root;

    public RepoStorage(AgentHubProperties properties, JGitEnvironment isolatedJGit) {
        this.root = properties.storageRoot().toAbsolutePath().normalize();
    }

    public Path dirOf(long id) {
        return root.resolve(id + ".git");
    }

    /** Creates an empty bare repository with HEAD on {@code refs/heads/main} and the settings of §6.1. */
    public void create(long id) {
        Path dir = dirOf(id);
        if (Files.exists(dir)) {
            throw new IllegalStateException("storage for repository " + id + " already exists: " + dir);
        }
        try {
            Files.createDirectories(root);
            try (Repository db = new FileRepositoryBuilder().setGitDir(dir.toFile()).setBare()
                    .setInitialBranch(Repo.INITIAL_BRANCH).build()) {
                db.create(true);
                StoredConfig config = db.getConfig();
                config.setBoolean(ConfigConstants.CONFIG_RECEIVE_SECTION, null, ConfigConstants.CONFIG_KEY_AUTOGC,
                        false);
                config.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
                        ConfigConstants.CONFIG_KEY_LOGALLREFUPDATES, true);
                config.setInt(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_AUTO, 0);
                config.save();
            }
        } catch (Exception e) {
            deleteQuietly(id);
            throw new IllegalStateException("cannot create repository storage " + dir, e);
        }
    }

    /** Opens through {@link RepositoryCache}; the caller closes the returned handle. */
    public Repository open(long id) throws IOException {
        return RepositoryCache.open(RepositoryCache.FileKey.exact(dirOf(id).toFile(), FS.DETECTED), true);
    }

    /** Server-made root commit of {@code files} on a branch that must not exist yet. */
    public ObjectId commitInitialFiles(long id, String branch, Map<String, String> files, PersonIdent author,
            PersonIdent committer, String message) throws IOException {
        try (Repository db = open(id); ObjectInserter inserter = db.newObjectInserter()) {
            SortedMap<String, ObjectId> blobs = new TreeMap<>();
            for (Map.Entry<String, String> file : files.entrySet()) {
                blobs.put(file.getKey(), inserter.insert(Constants.OBJ_BLOB,
                        file.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            TreeFormatter tree = new TreeFormatter();
            blobs.forEach((name, blob) -> tree.append(name, FileMode.REGULAR_FILE, blob));
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(inserter.insert(tree));
            commit.setAuthor(author);
            commit.setCommitter(committer);
            commit.setMessage(message);
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            RefUpdate update = db.updateRef(Constants.R_HEADS + branch);
            update.setNewObjectId(commitId);
            update.setExpectedOldObjectId(ObjectId.zeroId());
            update.setRefLogIdent(committer);
            update.setRefLogMessage("initial commit", false);
            RefUpdate.Result result = update.update();
            if (result != RefUpdate.Result.NEW) {
                throw new IOException("cannot create " + branch + ": " + result);
            }
            return commitId;
        }
    }

    public List<Ref> branches(long id) throws IOException {
        try (Repository db = open(id)) {
            return db.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
        }
    }

    public boolean branchExists(long id, String branch) throws IOException {
        try (Repository db = open(id)) {
            return db.exactRef(Constants.R_HEADS + branch) != null;
        }
    }

    /** Points the symbolic {@code HEAD} at another branch (§6.1). */
    public void linkHead(long id, String branch) throws IOException {
        try (Repository db = open(id)) {
            RefUpdate.Result result = db.updateRef(Constants.HEAD).link(Constants.R_HEADS + branch);
            if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NEW
                    && result != RefUpdate.Result.NO_CHANGE) {
                throw new IOException("cannot point HEAD at " + branch + ": " + result);
            }
        }
    }

    public long sizeOf(long id) {
        try (Stream<Path> files = Files.walk(dirOf(id))) {
            return files.filter(Files::isRegularFile).mapToLong(file -> file.toFile().length()).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Closes the cached instance and removes the directory; false when the OS still holds files (Windows). */
    public boolean delete(long id) {
        File dir = dirOf(id).toFile();
        if (!dir.exists()) {
            return true;
        }
        try {
            RepositoryCache.close(open(id));
        } catch (IOException notARepository) {
            // nothing cached; just remove the files
        }
        return deleteQuietly(id);
    }

    private boolean deleteQuietly(long id) {
        try {
            FileUtils.delete(dirOf(id).toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
