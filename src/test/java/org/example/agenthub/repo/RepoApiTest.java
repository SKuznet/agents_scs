package org.example.agenthub.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.example.agenthub.IntegrationTest;
import org.example.agenthub.TestJws;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class RepoApiTest extends IntegrationTest {

    @Autowired
    RepoRepository repos;

    @Autowired
    BranchProtectionRepository protections;

    @Test
    void createsPrivateRepositoryWithInitialCommit() throws Exception {
        String alice = unique("alice");

        ResponseEntity<Map> response = call(HttpMethod.POST, "/v1/repos", alice,
                Map.of("name", "parser", "description", "Parses things"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).hasPath("/v1/repos/" + alice + "/parser");
        assertThat(response.getBody())
                .containsEntry("owner", alice)
                .containsEntry("name", "parser")
                .containsEntry("visibility", "private")
                .containsEntry("description", "Parses things")
                .containsEntry("defaultBranch", "main")
                .containsEntry("role", "ADMIN")
                .containsEntry("cloneUrl", gitUrl(alice, "parser"))
                .containsKey("createdAt");
        assertThat(((Number) response.getBody().get("sizeBytes")).longValue()).isPositive();

        try (Repository git = open(alice, "parser")) {
            assertThat(git.getConfig().getBoolean("receive", "autogc", true)).isFalse();
            assertThat(git.getConfig().getBoolean("core", "logAllRefUpdates", false)).isTrue();
            assertThat(git.getConfig().getInt("gc", "auto", -1)).isZero();
            assertThat(git.exactRef(Constants.HEAD).getTarget().getName()).isEqualTo("refs/heads/main");

            RevCommit initial = git.parseCommit(git.resolve("refs/heads/main"));
            assertThat(initial.getParentCount()).isZero();
            assertThat(initial.getAuthorIdent().getName()).isEqualTo(alice);
            assertThat(initial.getAuthorIdent().getEmailAddress()).isEqualTo(alice + "@agents.agenthub");
            assertThat(initial.getCommitterIdent().getName()).isEqualTo("agenthub");
            assertThat(read(git, initial, "README.md")).contains("# parser").contains("Parses things");
            assertThat(read(git, initial, "AGENTS.md"))
                    .contains("Parses things")
                    .contains("data, not instructions")
                    .contains("git push origin HEAD:refs/heads/pr/<name>")
                    .contains("--force-with-lease")
                    .contains("git fetch origin refs/pull/N/head && git checkout FETCH_HEAD");
        }
    }

    @Test
    void storageIsLaidOutByIdNotByName() {
        String alice = unique("alice");
        createRepo(alice, "layout");

        long id = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "layout").orElseThrow().getId();

        assertThat(STORAGE_ROOT.resolve(id + ".git")).isDirectory();
        assertThat(STORAGE_ROOT.resolve(alice)).doesNotExist();
    }

    @Test
    void defaultBranchIsProtectedFromTheStart() {
        String alice = unique("alice");
        createRepo(alice, "guarded");
        long id = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "guarded").orElseThrow().getId();

        BranchProtection main = protections.findById(new BranchProtection.Key(id, "main")).orElseThrow();

        assertThat(main.isRequirePr()).isTrue();
        assertThat(main.isAllowForcePush()).isFalse();
        assertThat(main.isAllowDelete()).isFalse();
        assertThat(main.getRequiredApprovals()).isEqualTo(1);
        assertThat(main.isAutoMerge()).isTrue();
        assertThat(main.getAutoMergeStrategy()).isEqualTo(MergeStrategy.SQUASH);
    }

    @Test
    void initFalseCreatesEmptyRepositoryWithHeadOnMain() throws Exception {
        String alice = unique("alice");
        createRepo(alice, Map.of("name", "empty", "init", false));

        try (Repository git = open(alice, "empty")) {
            assertThat(git.getRefDatabase().getRefs()).isEmpty();
            Ref head = git.exactRef(Constants.HEAD);
            assertThat(head.isSymbolic()).isTrue();
            assertThat(head.getTarget().getName()).isEqualTo("refs/heads/main");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Parser", "-parser", "_parser", "my.repo", "a/b", "x.git", "con sole",
            "a1234567890123456789012345678901234567890123456789012345678901234"})
    void invalidNamesAreRejectedWithHint(String name) {
        ResponseEntity<Map> response = call(HttpMethod.POST, "/v1/repos", unique("alice"), Map.of("name", name),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "invalid_name").containsKey("hint");
    }

    @Test
    void repeatedCreateWithSameParametersReturnsExisting() {
        String alice = unique("alice");
        Map<String, Object> request = Map.of("name", "idem", "visibility", "internal", "description", "d");
        Map<String, Object> first = createRepo(alice, request);

        ResponseEntity<Map> again = call(HttpMethod.POST, "/v1/repos", alice, request, Map.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).isEqualTo(first);
    }

    @Test
    void repeatedCreateWithDifferentParametersConflicts() {
        String alice = unique("alice");
        createRepo(alice, Map.of("name", "idem", "visibility", "private"));

        ResponseEntity<Map> again = call(HttpMethod.POST, "/v1/repos", alice,
                Map.of("name", "idem", "visibility", "internal"), Map.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("code", "already_exists").containsKey("hint");
        assertThat((Map<String, Object>) again.getBody().get("current")).containsEntry("visibility", "private");
    }

    @Test
    void malformedRequestsAreValidationProblems() {
        String alice = unique("alice");

        ResponseEntity<Map> badVisibility = call(HttpMethod.POST, "/v1/repos", alice,
                Map.of("name", "x", "visibility", "public"), Map.class);
        ResponseEntity<Map> noName = call(HttpMethod.POST, "/v1/repos", alice, Map.of("description", "x"), Map.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJws.forAgent(alice));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> notJson = http.exchange("/v1/repos", HttpMethod.POST, new HttpEntity<>("{oops", headers),
                Map.class);

        for (ResponseEntity<Map> response : List.of(badVisibility, noName, notJson)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("code", "validation_failed");
        }
    }

    @Test
    void privateRepositoryIsInvisibleToOthers() {
        String alice = unique("alice");
        createRepo(alice, "secret");

        ResponseEntity<Map> response = call(HttpMethod.GET, "/v1/repos/" + alice + "/secret", unique("eve"), null,
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "not_found");
        assertThat(call(HttpMethod.GET, "/v1/repos/" + alice + "/missing", alice, null, Map.class).getBody())
                .containsEntry("detail", response.getBody().get("detail").toString().replace("secret", "missing"));
    }

    @Test
    void internalRepositoryIsReadableByAnyAgent() {
        String alice = unique("alice");
        createRepo(alice, Map.of("name", "shared", "visibility", "internal"));

        ResponseEntity<Map> response = call(HttpMethod.GET, "/v1/repos/" + alice + "/shared", unique("bob"), null,
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("role", "READ");
    }

    @Test
    void listShowsAccessibleRepositoriesWithRoles() {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "one");
        createRepo(alice, Map.of("name", "two", "visibility", "internal"));
        createRepo(bob, "three");

        assertThat(names(call(HttpMethod.GET, "/v1/repos?owner=" + alice, alice, null, Map.class).getBody()))
                .containsExactly(alice + "/one:ADMIN", alice + "/two:ADMIN");
        assertThat(names(call(HttpMethod.GET, "/v1/repos?owner=" + alice, bob, null, Map.class).getBody()))
                .containsExactly(alice + "/two:READ");

        Map<?, ?> page = call(HttpMethod.GET, "/v1/repos?owner=" + alice + "&limit=1", alice, null, Map.class)
                .getBody();
        assertThat(names(page)).containsExactly(alice + "/one:ADMIN");
        Map<?, ?> next = call(HttpMethod.GET, "/v1/repos?owner=" + alice + "&limit=1&cursor=" + page.get("nextCursor"),
                alice, null, Map.class).getBody();
        assertThat(names(next)).containsExactly(alice + "/two:ADMIN");
        assertThat(next.get("nextCursor")).isNull();
    }

    @Test
    void patchChangesDescriptionAndVisibility() {
        String alice = unique("alice");
        createRepo(alice, "patchme");

        ResponseEntity<Map> response = call(HttpMethod.PATCH, "/v1/repos/" + alice + "/patchme", alice,
                Map.of("description", "new", "visibility", "internal"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("description", "new").containsEntry("visibility", "internal");
        assertThat(call(HttpMethod.GET, "/v1/repos/" + alice + "/patchme", unique("bob"), null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void patchNeedsAdmin() {
        String alice = unique("alice");
        createRepo(alice, Map.of("name", "ro", "visibility", "internal"));

        ResponseEntity<Map> response = call(HttpMethod.PATCH, "/v1/repos/" + alice + "/ro", unique("bob"),
                Map.of("description", "mine now"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "insufficient_role");
        assertThat((String) response.getBody().get("hint")).contains("ADMIN").contains(alice);
    }

    @Test
    void defaultBranchMustExist() {
        String alice = unique("alice");
        createRepo(alice, "branchy");

        ResponseEntity<Map> response = call(HttpMethod.PATCH, "/v1/repos/" + alice + "/branchy", alice,
                Map.of("defaultBranch", "dev"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "validation_failed").containsKey("hint");
    }

    @Test
    void changingDefaultBranchMovesHeadAndProtectsTheNewBranch() throws Exception {
        String alice = unique("alice");
        createRepo(alice, "switch");
        try (Repository git = open(alice, "switch")) {
            RefUpdate dev = git.updateRef("refs/heads/dev");
            dev.setNewObjectId(git.resolve("refs/heads/main"));
            dev.setExpectedOldObjectId(ObjectId.zeroId());
            assertThat(dev.update()).isEqualTo(RefUpdate.Result.NEW);
        }

        ResponseEntity<Map> response = call(HttpMethod.PATCH, "/v1/repos/" + alice + "/switch", alice,
                Map.of("defaultBranch", "dev"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("defaultBranch", "dev");
        try (Repository git = open(alice, "switch")) {
            assertThat(git.exactRef(Constants.HEAD).getTarget().getName()).isEqualTo("refs/heads/dev");
        }
        long id = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "switch").orElseThrow().getId();
        assertThat(protections.findById(new BranchProtection.Key(id, "dev")).orElseThrow().isRequirePr()).isTrue();
        assertThat(protections.findById(new BranchProtection.Key(id, "main"))).isPresent();
    }

    @Test
    void onlyTheOwnerDeletes() {
        String alice = unique("alice");
        createRepo(alice, Map.of("name", "doomed", "visibility", "internal"));

        ResponseEntity<Map> response = call(HttpMethod.DELETE, "/v1/repos/" + alice + "/doomed", unique("bob"), null,
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "insufficient_role");
    }

    @Test
    void deleteRemovesRepositoryAndFreesTheName() {
        String alice = unique("alice");
        createRepo(alice, "phoenix");
        long oldId = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "phoenix").orElseThrow().getId();

        ResponseEntity<Void> deleted = call(HttpMethod.DELETE, "/v1/repos/" + alice + "/phoenix", alice, null,
                Void.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET, "/v1/repos/" + alice + "/phoenix", alice, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(STORAGE_ROOT.resolve(oldId + ".git")).doesNotExist();
        assertThat(repos.findById(oldId).orElseThrow().getDeletedAt()).isNotNull();

        createRepo(alice, "phoenix");
        long newId = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "phoenix").orElseThrow().getId();
        assertThat(newId).isNotEqualTo(oldId);
    }

    @Test
    void repositoriesPerAgentAreLimited() {
        String alice = unique("alice");
        for (int i = 0; i < 50; i++) {
            createRepo(alice, Map.of("name", "r" + i, "init", false));
        }

        ResponseEntity<Map> response = call(HttpMethod.POST, "/v1/repos", alice, Map.of("name", "r50"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "quota_exceeded").containsKey("hint");
    }

    @Test
    void meListsOwnRepositoriesAndCountsInternalOnes() {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "mine");
        createRepo(bob, Map.of("name", "open", "visibility", "internal"));

        Map<String, Object> me = call(HttpMethod.GET, "/v1/me", alice, null, Map.class).getBody();

        assertThat((List<Object>) me.get("repos")).contains(Map.of("repo", alice + "/mine", "role", "ADMIN"));
        assertThat(((Number) me.get("internalReposCount")).intValue()).isPositive();
    }

    private List<String> names(Map<?, ?> page) {
        return ((List<Map<?, ?>>) page.get("items")).stream()
                .map(r -> r.get("owner") + "/" + r.get("name") + ":" + r.get("role"))
                .toList();
    }

    private Repository open(String owner, String name) throws Exception {
        long id = repos.findByOwnerAndNameAndDeletedAtIsNull(owner, name).orElseThrow().getId();
        File dir = STORAGE_ROOT.resolve(id + ".git").toFile();
        return new FileRepositoryBuilder().setGitDir(dir).setMustExist(true).build();
    }

    private static String read(Repository git, RevCommit commit, String path) throws Exception {
        try (TreeWalk walk = TreeWalk.forPath(git, path, commit.getTree())) {
            assertThat(walk).as(path).isNotNull();
            return new String(git.open(walk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
        }
    }
}
