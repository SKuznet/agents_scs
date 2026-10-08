package org.example.agenthub.git;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.example.agenthub.IntegrationTest;
import org.example.agenthub.TestJws;
import org.example.agenthub.repo.RepoRepository;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Git over HTTP with the JGit client, the way §16 prescribes. */
public abstract class GitIntegrationTest extends IntegrationTest {

    @TempDir
    protected Path workspace;

    @Autowired
    protected RepoRepository repos;

    protected static CredentialsProvider credentials(String agent) {
        return new UsernamePasswordCredentialsProvider("x", TestJws.forAgent(agent));
    }

    protected Git cloneAs(String agent, String owner, String repo) throws GitAPIException, IOException {
        File dir = Files.createTempDirectory(workspace, agent).toFile();
        return Git.cloneRepository().setURI(gitUrl(owner, repo)).setDirectory(dir)
                .setCredentialsProvider(credentials(agent)).call();
    }

    protected static RevCommit commitFile(Git git, String path, String content) throws Exception {
        Path file = git.getRepository().getWorkTree().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git.add().addFilepattern(path).call();
        return git.commit().setMessage("change " + path).setAuthor("tester", "tester@example.org")
                .setCommitter("tester", "tester@example.org").setSign(false).call();
    }

    protected static RemoteRefUpdate push(Git git, String agent, String refSpec, String... pushOptions)
            throws GitAPIException {
        PushCommand push = git.push().setCredentialsProvider(credentials(agent)).setRefSpecs(new RefSpec(refSpec));
        if (pushOptions.length > 0) {
            push.setPushOptions(List.of(pushOptions));
        }
        Iterable<PushResult> results = push.call();
        PushResult result = results.iterator().next();
        return result.getRemoteUpdates().iterator().next();
    }

    protected static PushResult pushResult(Git git, String agent, String... refSpecs) throws GitAPIException {
        return git.push().setCredentialsProvider(credentials(agent))
                .setRefSpecs(Arrays.stream(refSpecs).map(RefSpec::new).toList())
                .call().iterator().next();
    }

    protected Repository serverRepo(String owner, String name) throws IOException {
        long id = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, name).orElseThrow().getId();
        return new FileRepositoryBuilder().setGitDir(STORAGE_ROOT.resolve(id + ".git").toFile()).setMustExist(true)
                .build();
    }
}
