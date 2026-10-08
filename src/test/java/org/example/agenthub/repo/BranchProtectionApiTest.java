package org.example.agenthub.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** §6.4 and §10.2: branch protection API; the push policy reads it on every push. */
class BranchProtectionApiTest extends GitIntegrationTest {

    String owner;
    String bob;
    String carol;
    String base;

    @BeforeEach
    void repository() {
        owner = unique("olga");
        bob = unique("bob");
        carol = unique("carol");
        createRepo(owner, "proj");
        grant(owner, "proj", bob, "PROPOSE");
        grant(owner, "proj", carol, "WRITE");
        base = "/v1/repos/" + owner + "/proj/protection?branch=";
    }

    static Map<String, Object> settings(boolean requirePr, boolean allowForcePush, boolean allowDelete,
            int requiredApprovals, boolean autoMerge) {
        Map<String, Object> body = new HashMap<>();
        body.put("requirePr", requirePr);
        body.put("allowForcePush", allowForcePush);
        body.put("allowDelete", allowDelete);
        body.put("requiredApprovals", requiredApprovals);
        body.put("autoMerge", autoMerge);
        body.put("autoMergeStrategy", "SQUASH");
        return body;
    }

    ResponseEntity<Map> put(String agent, String branch, Map<String, Object> body) {
        return call(HttpMethod.PUT, base + branch, agent, body, Map.class);
    }

    Map<String, Object> get(String branch) {
        ResponseEntity<Map> response = call(HttpMethod.GET, base + branch, owner, null, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    static void refused(ResponseEntity<Map> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody()).containsEntry("code", code).containsKey("hint");
    }

    @Test
    void defaultBranchHasTheDefaultRecord() {
        assertThat(get("main")).containsEntry("branch", "main").containsEntry("requirePr", true)
                .containsEntry("allowForcePush", false).containsEntry("allowDelete", false)
                .containsEntry("requiredApprovals", 1).containsEntry("autoMerge", true)
                .containsEntry("autoMergeStrategy", "SQUASH").containsEntry("default", true)
                .containsEntry("configured", true);
    }

    @Test
    void branchWithoutRecordIsUnprotected() {
        assertThat(get("feature/x")).containsEntry("branch", "feature/x").containsEntry("requirePr", false)
                .containsEntry("allowForcePush", true).containsEntry("allowDelete", true)
                .containsEntry("requiredApprovals", 0).containsEntry("autoMerge", false)
                .containsEntry("autoMergeStrategy", null).containsEntry("default", false)
                .containsEntry("configured", false);
    }

    @Test
    void adminsReplaceAndRemoveProtectionOthersOnlyRead() {
        String reader = unique("rita");
        grant(owner, "proj", reader, "READ");

        refused(put(carol, "release", settings(true, false, false, 2, false)), HttpStatus.FORBIDDEN,
                "insufficient_role");
        assertThat(call(HttpMethod.GET, base + "release", reader, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> replaced = put(owner, "release", settings(true, false, false, 2, false));
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("release")).containsEntry("requirePr", true).containsEntry("requiredApprovals", 2)
                .containsEntry("configured", true);
        assertThat(call(HttpMethod.GET, "/v1/repos/" + owner + "/proj/branches", owner, null, List.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(call(HttpMethod.DELETE, base + "release", owner, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("release")).containsEntry("configured", false).containsEntry("requirePr", false);
        assertThat(call(HttpMethod.GET, base + "release", unique("eve"), null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void defaultBranchKeepsItsProtectionAndCanNeverBeDeleted() {
        refused(call(HttpMethod.DELETE, base + "main", owner, null, Map.class), HttpStatus.BAD_REQUEST,
                "validation_failed");
        refused(put(owner, "main", settings(true, false, true, 1, true)), HttpStatus.BAD_REQUEST,
                "validation_failed");
    }

    @Test
    void invalidRequestsAreRefused() {
        Map<String, Object> missing = settings(true, false, false, 1, true);
        missing.remove("allowDelete");

        refused(put(owner, "main", missing), HttpStatus.BAD_REQUEST, "validation_failed");
        refused(put(owner, "main", settings(true, false, false, -1, true)), HttpStatus.BAD_REQUEST,
                "validation_failed");
        refused(put(owner, "Bad..Name", settings(true, false, false, 1, true)), HttpStatus.BAD_REQUEST,
                "invalid_ref_name");
        refused(call(HttpMethod.GET, "/v1/repos/" + owner + "/proj/protection", owner, null, Map.class),
                HttpStatus.BAD_REQUEST, "validation_failed");
    }

    @Test
    void pushPolicyFollowsTheProtectionAtOnce() throws Exception {
        try (Git clone = cloneAs(carol, owner, "proj")) {
            commitFile(clone, "a.txt", "a");
            assertThat(push(clone, carol, "HEAD:refs/heads/release").getStatus())
                    .isEqualTo(RemoteRefUpdate.Status.OK);
            put(owner, "release", settings(true, false, false, 1, false));
            commitFile(clone, "b.txt", "b");
            assertThat(push(clone, carol, "HEAD:refs/heads/release").getMessage()).isEqualTo("protected_branch");

            put(owner, "main", settings(false, false, false, 1, true));
            assertThat(push(clone, carol, "HEAD:refs/heads/main").getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);
        }
    }

    @Test
    void zeroRequiredApprovalsDoesNotWeakenD8() throws Exception {
        put(owner, "main", settings(true, false, false, 0, false));
        RevCommit bobs;
        RevCommit carols;
        try (Git clone = cloneAs(bob, owner, "proj")) {
            bobs = commitFile(clone, "bob.txt", "bob");
            push(clone, bob, "HEAD:refs/heads/pr/bob");
        }
        try (Git clone = cloneAs(carol, owner, "proj")) {
            carols = commitFile(clone, "carol.txt", "carol");
            push(clone, carol, "HEAD:refs/heads/pr/carol");
        }
        String pulls = "/v1/repos/" + owner + "/proj/pulls/";

        refused(call(HttpMethod.POST, pulls + "1/merge", carol, Map.of("expectedHeadSha", bobs.name()), Map.class),
                HttpStatus.FORBIDDEN, "approvals_required");
        assertThat(call(HttpMethod.POST, pulls + "2/merge", carol, Map.of("expectedHeadSha", carols.name()),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
