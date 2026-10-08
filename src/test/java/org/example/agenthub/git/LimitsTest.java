package org.example.agenthub.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.TooLargeObjectInPackException;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.example.agenthub.TestJws;
import org.example.agenthub.maintenance.GcScheduler;
import org.example.agenthub.pr.Mergeable;
import org.example.agenthub.pr.PullRequestRepository;
import org.example.agenthub.repo.RepoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.unit.DataSize;

/** §15 limits end to end, with small values in a context of its own. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "agenthub.limits.max-repos-per-agent=2",
        "agenthub.limits.max-object-size=4KB",
        "agenthub.limits.max-repo-size=48KB",
        "agenthub.limits.max-commands-per-push=2",
        "agenthub.limits.max-refs-per-repo=3",
        "agenthub.limits.max-json-body=1KB",
        "agenthub.limits.max-open-pulls-per-agent=1",
        "agenthub.limits.max-new-pulls-per-day=2",
        "agenthub.limits.max-revisions-per-pull=2",
        "agenthub.mergeability-budget=0s",
        "agenthub.gc.interval=0s",
        "agenthub.sweep-interval=0s",
        "agenthub.gc.prune-expire=0s"})
class LimitsTest {

    static final Path STORAGE_ROOT;

    static {
        try {
            STORAGE_ROOT = Files.createTempDirectory("agenthub-limits");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agenthub.jws-secret", () -> TestJws.SECRET);
        registry.add("agenthub.storage-root", STORAGE_ROOT::toString);
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:agenthub-limits;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    RepoRepository repos;

    @Autowired
    GcScheduler gc;

    @Autowired
    PullRequestRepository pulls;

    @LocalServerPort
    int port;

    @TempDir
    Path workspace;

    ResponseEntity<Map> call(HttpMethod method, String path, String agent, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJws.forAgent(agent));
        return http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    Git cloneAs(String agent, String repo) throws Exception {
        return Git.cloneRepository().setURI("http://localhost:" + port + "/git/" + agent + "/" + repo + ".git")
                .setDirectory(Files.createTempDirectory(workspace, "clone").toFile())
                .setCredentialsProvider(GitIntegrationTest.credentials(agent)).call();
    }

    static String unique(String prefix) {
        return prefix + "-" + Long.toHexString(System.nanoTime());
    }

    @Test
    void repositoriesPerAgent() {
        String alice = unique("alice");
        call(HttpMethod.POST, "/v1/repos", alice, Map.of("name", "a", "init", false));
        call(HttpMethod.POST, "/v1/repos", alice, Map.of("name", "b", "init", false));

        ResponseEntity<Map> third = call(HttpMethod.POST, "/v1/repos", alice, Map.of("name", "c"));

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(third.getBody()).containsEntry("code", "quota_exceeded");
    }

    @Test
    void jsonBodySize() {
        ResponseEntity<Map> response = call(HttpMethod.POST, "/v1/repos", unique("alice"),
                Map.of("name", "x", "description", "d".repeat(2000)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).containsEntry("code", "payload_too_large");
    }

    @Test
    void commandsPerPushAndRefsPerRepository() throws Exception {
        String alice = unique("alice");
        call(HttpMethod.POST, "/v1/repos", alice, Map.of("name", "refs"));

        try (Git clone = cloneAs(alice, "refs")) {
            PushResult tooMany = GitIntegrationTest.pushResult(clone, alice, "HEAD:refs/heads/a", "HEAD:refs/heads/b",
                    "HEAD:refs/heads/c");
            assertThat(tooMany.getRemoteUpdates()).allSatisfy(update -> {
                assertThat(update.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
                assertThat(update.getMessage()).isEqualTo("too_many_commands");
            });

            PushResult two = GitIntegrationTest.pushResult(clone, alice, "HEAD:refs/heads/a", "HEAD:refs/heads/b");
            assertThat(two.getRemoteUpdates()).allSatisfy(
                    update -> assertThat(update.getStatus()).isEqualTo(RemoteRefUpdate.Status.OK));

            RemoteRefUpdate fourth = GitIntegrationTest.push(clone, alice, "HEAD:refs/tags/v1");
            assertThat(fourth.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
            assertThat(fourth.getMessage()).isEqualTo("quota_exceeded");
        }
    }

    @Test
    void objectSize() throws Exception {
        String alice = unique("alice");
        call(HttpMethod.POST, "/v1/repos", alice, Map.of("name", "huge"));

        try (Git clone = cloneAs(alice, "huge")) {
            GitIntegrationTest.commitFile(clone, "huge.bin", randomText(8 * 1024));
            assertThatThrownBy(() -> GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/huge"))
                    .hasRootCauseInstanceOf(TooLargeObjectInPackException.class);
        }
    }

    @Test
    void overQuotaRepositoryShrinksByDeletingBranchesAndGc() throws Exception {
        String alice = unique("alice");
        call(HttpMethod.POST, "/v1/repos", alice, Map.of("name", "big"));
        long id = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "big").orElseThrow().getId();

        try (Git clone = cloneAs(alice, "big")) {
            for (int i = 0; i < 40; i++) {
                GitIntegrationTest.commitFile(clone, "part" + i + ".bin", randomText(3 * 1024));
            }
            assertThat(GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/parts").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
            await().atMost(Duration.ofSeconds(10)).until(() -> repoSize(id) > DataSize.ofKilobytes(48).toBytes());

            RemoteRefUpdate refused = GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/more");
            assertThat(refused.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
            assertThat(refused.getMessage()).isEqualTo("quota_exceeded");

            assertThat(GitIntegrationTest.push(clone, alice, ":refs/heads/parts").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
            await().until(() -> gc.collect(id));
            assertThat(repoSize(id)).isLessThan(DataSize.ofKilobytes(48).toBytes());

            clone.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("origin/main").call();
            GitIntegrationTest.commitFile(clone, "small.txt", "small");
            assertThat(GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/more").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
        }
    }

    @Test
    void theContentApiWritesNothingIntoAnOverQuotaRepository() throws Exception {
        String alice = unique("alice");
        call(HttpMethod.POST, "/v1/repos", alice, Map.of("name", "full"));
        long id = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "full").orElseThrow().getId();
        String tip;
        try (Git clone = cloneAs(alice, "full")) {
            for (int i = 0; i < 40; i++) {
                GitIntegrationTest.commitFile(clone, "part" + i + ".bin", randomText(3 * 1024));
            }
            tip = clone.getRepository().resolve("HEAD").name();
            assertThat(GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/parts").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> repoSize(id) > DataSize.ofKilobytes(48).toBytes());
        List<Map<String, String>> files = List.of(Map.of("path", "a.txt", "content", "a"));

        ResponseEntity<Map> commit = call(HttpMethod.POST, "/v1/repos/" + alice + "/full/commits", alice,
                Map.of("branch", "parts", "parentSha", tip, "message", "more", "files", files));
        ResponseEntity<Map> pull = call(HttpMethod.POST, "/v1/repos/" + alice + "/full/pulls", alice,
                Map.of("name", "more", "baseSha", tip, "files", files));

        assertThat(commit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(commit.getBody()).containsEntry("code", "quota_exceeded");
        assertThat(pull.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(pull.getBody()).containsEntry("code", "quota_exceeded");
    }

    @Test
    void pullRequestQuotas() throws Exception {
        String alice = unique("alice");
        call(HttpMethod.POST, "/v1/repos", alice, Map.of("name", "prs"));
        try (Git clone = cloneAs(alice, "prs")) {
            GitIntegrationTest.commitFile(clone, "a.txt", "1");
            assertThat(GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/pr/a").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
            GitIntegrationTest.commitFile(clone, "a.txt", "2");
            assertThat(GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/pr/a").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
            GitIntegrationTest.commitFile(clone, "a.txt", "3");
            assertQuota(GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/pr/a"));
            assertQuota(GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/pr/b"));

            close(alice, 1);
            assertThat(GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/pr/b").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
            close(alice, 2);
            assertQuota(GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/pr/c"));
        }
    }

    @Test
    void mergeabilityBeyondTheSyncBudgetIsComputedInTheBackground() throws Exception {
        String alice = unique("alice");
        call(HttpMethod.POST, "/v1/repos", alice, Map.of("name", "slow"));
        long repoId = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "slow").orElseThrow().getId();

        try (Git clone = cloneAs(alice, "slow")) {
            GitIntegrationTest.commitFile(clone, "a.txt", "a");
            assertThat(GitIntegrationTest.push(clone, alice, "HEAD:refs/heads/pr/slow").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
        }

        await().atMost(Duration.ofSeconds(10)).until(
                () -> pulls.findByRepoIdAndNumber(repoId, 1).orElseThrow().getMergeable() == Mergeable.CLEAN);
    }

    private static void assertQuota(RemoteRefUpdate update) {
        assertThat(update.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
        assertThat(update.getMessage()).isEqualTo("quota_exceeded");
    }

    private void close(String owner, int number) {
        assertThat(call(HttpMethod.PATCH, "/v1/repos/" + owner + "/prs/pulls/" + number, owner,
                Map.of("state", "CLOSED")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private long repoSize(long id) {
        return repos.findById(id).orElseThrow().getSizeBytes();
    }

    private static String randomText(int size) {
        Random random = new Random();
        StringBuilder text = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            text.append((char) ('!' + random.nextInt(90)));
        }
        return text.toString();
    }
}
