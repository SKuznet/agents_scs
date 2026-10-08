package org.example.agenthub.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.example.agenthub.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GrantApiTest extends IntegrationTest {

    private ResponseEntity<Map> put(String caller, String owner, String repo, String agent, Object role) {
        return call(HttpMethod.PUT, "/v1/repos/" + owner + "/" + repo + "/grants/" + agent, caller,
                Map.of("role", role), Map.class);
    }

    @Test
    void grantingToAnAgentThatNeverConnectedWarns() {
        String alice = unique("alice");
        String bbo = unique("bbo");
        createRepo(alice, "proj");

        ResponseEntity<Map> response = put(alice, alice, "proj", bbo, "PROPOSE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("agent", bbo).containsEntry("role", "PROPOSE")
                .containsEntry("warning", "agent '" + bbo + "' has never connected");
    }

    @Test
    void grantingToAKnownAgentHasNoWarningAndIsIdempotent() {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "proj");
        call(HttpMethod.GET, "/v1/me", bob, null, Map.class);

        ResponseEntity<Map> first = put(alice, alice, "proj", bob, "WRITE");
        ResponseEntity<Map> again = put(alice, alice, "proj", bob, "write");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("role", "WRITE").doesNotContainKey("warning");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).isEqualTo(first.getBody());
    }

    @Test
    void grantedAgentGetsItsRoleAndSeesTheRepository() {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "proj");
        assertThat(call(HttpMethod.GET, "/v1/repos/" + alice + "/proj", bob, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        grant(alice, "proj", bob, "PROPOSE");

        assertThat(call(HttpMethod.GET, "/v1/repos/" + alice + "/proj", bob, null, Map.class).getBody())
                .containsEntry("role", "PROPOSE");
        Map<?, ?> shared = call(HttpMethod.GET, "/v1/repos?sharedWithMe=true", bob, null, Map.class).getBody();
        assertThat((List<Map<?, ?>>) shared.get("items")).singleElement()
                .satisfies(repo -> assertThat(repo.get("name")).isEqualTo("proj"));
        Map<String, Object> me = call(HttpMethod.GET, "/v1/me", bob, null, Map.class).getBody();
        assertThat((List<Object>) me.get("repos")).containsExactly(Map.of("repo", alice + "/proj", "role", "PROPOSE"));
    }

    @Test
    void membersSeeTheGrantList() {
        String alice = unique("alice");
        String bob = unique("bob");
        String carol = unique("carol");
        createRepo(alice, "proj");
        grant(alice, "proj", bob, "READ");
        grant(alice, "proj", carol, "WRITE");

        ResponseEntity<List> response = call(HttpMethod.GET, "/v1/repos/" + alice + "/proj/grants", bob, null,
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(
                Map.of("agent", bob, "role", "READ", "grantedBy", alice),
                Map.of("agent", carol, "role", "WRITE", "grantedBy", alice));
    }

    @Test
    void onlyAdminsManageGrants() {
        String alice = unique("alice");
        String bob = unique("bob");
        String carol = unique("carol");
        createRepo(alice, "proj");
        grant(alice, "proj", bob, "WRITE");

        ResponseEntity<Map> response = put(bob, alice, "proj", carol, "READ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "insufficient_role");
        assertThat((String) response.getBody().get("hint")).contains(alice);

        grant(alice, "proj", bob, "ADMIN");
        assertThat(put(bob, alice, "proj", carol, "READ").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void strangersCannotEvenSeeGrants() {
        String alice = unique("alice");
        createRepo(alice, "proj");

        assertThat(call(HttpMethod.GET, "/v1/repos/" + alice + "/proj/grants", unique("eve"), null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(unique("eve"), alice, "proj", "eve", "ADMIN").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ownerAndServerIdentityCannotBeGranted() {
        String alice = unique("alice");
        createRepo(alice, "proj");

        for (String agent : List.of(alice, "agenthub")) {
            ResponseEntity<Map> response = put(alice, alice, "proj", agent, "READ");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("code", "validation_failed").containsKey("hint");

            ResponseEntity<Map> revoke = call(HttpMethod.DELETE, "/v1/repos/" + alice + "/proj/grants/" + agent,
                    alice, null, Map.class);
            assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bob", "-bob", "bob.smith"})
    void grantNeedsAValidHandle(String agent) {
        String alice = unique("alice");
        createRepo(alice, "proj");

        ResponseEntity<Map> response = put(alice, alice, "proj", agent, "READ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "invalid_name");
    }

    @Test
    void unknownRoleIsRejected() {
        String alice = unique("alice");
        createRepo(alice, "proj");

        ResponseEntity<Map> response = put(alice, alice, "proj", unique("bob"), "OWNER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "validation_failed");
    }

    @Test
    void revokingRemovesAccess() {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "proj");
        grant(alice, "proj", bob, "WRITE");

        ResponseEntity<Void> revoked = call(HttpMethod.DELETE, "/v1/repos/" + alice + "/proj/grants/" + bob, alice,
                null, Void.class);

        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET, "/v1/repos/" + alice + "/proj", bob, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, "/v1/repos/" + alice + "/proj/grants/" + bob, alice, null, Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
