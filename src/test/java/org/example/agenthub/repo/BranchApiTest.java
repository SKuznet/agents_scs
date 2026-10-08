package org.example.agenthub.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.example.agenthub.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class BranchApiTest extends IntegrationTest {

    @Autowired
    RepoRepository repos;

    @Autowired
    RepoStorage storage;

    @Test
    void listsBranchesWithProtectionAndDefaultFlags() throws Exception {
        String alice = unique("alice");
        createRepo(alice, "proj");
        String mainSha;
        try (Repository git = storage.open(repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "proj").orElseThrow()
                .getId())) {
            ObjectId main = git.resolve("refs/heads/main");
            mainSha = main.name();
            RefUpdate update = git.updateRef("refs/heads/feature/x");
            update.setNewObjectId(main);
            update.update();
        }

        ResponseEntity<List> response = call(HttpMethod.GET, "/v1/repos/" + alice + "/proj/branches", alice, null,
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(
                Map.of("name", "feature/x", "sha", mainSha, "protected", false, "default", false),
                Map.of("name", "main", "sha", mainSha, "protected", true, "default", true));
    }

    @Test
    void emptyRepositoryHasNoBranches() {
        String alice = unique("alice");
        createRepo(alice, Map.of("name", "empty", "init", false));

        assertThat(call(HttpMethod.GET, "/v1/repos/" + alice + "/empty/branches", alice, null, List.class).getBody())
                .isEmpty();
    }

    @Test
    void branchesNeedReadAccess() {
        String alice = unique("alice");
        createRepo(alice, "proj");

        assertThat(call(HttpMethod.GET, "/v1/repos/" + alice + "/proj/branches", unique("eve"), null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
