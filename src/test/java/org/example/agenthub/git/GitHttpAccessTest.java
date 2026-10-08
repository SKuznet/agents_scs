package org.example.agenthub.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.example.agenthub.IntegrationTest;
import org.example.agenthub.TestJws;
import org.example.agenthub.repo.RepoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/** Refusals of {@code GitAccessFilter}, read directly: the JGit client does not expose response bodies. */
class GitHttpAccessTest extends IntegrationTest {

    static final String UPLOAD_ADVERTISEMENT = "/info/refs?service=git-upload-pack";
    static final String RECEIVE_ADVERTISEMENT = "/info/refs?service=git-receive-pack";

    @Autowired
    RepoRepository repos;

    @Autowired
    TransactionTemplate tx;

    ResponseEntity<String> git(HttpMethod method, String path, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return http.exchange("/git/" + path, method, new HttpEntity<>(headers), String.class);
    }

    static String bearer(String agent) {
        return "Bearer " + TestJws.forAgent(agent);
    }

    static String basic(String password) {
        return "Basic " + Base64.getEncoder().encodeToString(("anyone:" + password).getBytes(StandardCharsets.UTF_8));
    }

    static void assertPlainText(ResponseEntity<String> response) {
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue();
    }

    @Test
    void missingCredentialsGetBasicChallengeSoGitSendsThem() {
        String alice = unique("alice");
        createRepo(alice, "proj");

        for (String path : new String[] {alice + "/proj.git" + UPLOAD_ADVERTISEMENT, "nobody/nothing.git/info/refs"}) {
            ResponseEntity<String> response = git(HttpMethod.GET, path, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                    .isEqualTo("Basic realm=\"agenthub\"");
            assertPlainText(response);
        }
    }

    @Test
    void invalidTokenIs403PlainTextSoGitShowsTheReason() {
        String alice = unique("alice");
        createRepo(alice, "proj");

        ResponseEntity<String> response = git(HttpMethod.GET, alice + "/proj.git" + UPLOAD_ADVERTISEMENT,
                "Bearer not-a-jws");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertPlainText(response);
        assertThat(response.getBody()).startsWith("invalid_token: ").contains("\nhint: ");
    }

    @Test
    void expiredTokenIs403PlainText() {
        String alice = unique("alice");
        createRepo(alice, "proj");
        String expired = TestJws.sign(alice, TestJws.SECRET, Duration.ofMinutes(-5));

        ResponseEntity<String> response = git(HttpMethod.GET, alice + "/proj.git" + UPLOAD_ADVERTISEMENT,
                basic(expired));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).startsWith("token_expired: ").contains("hint: ");
    }

    @Test
    void tokenIsAcceptedAsBearerOrAsBasicPassword() {
        String alice = unique("alice");
        createRepo(alice, "proj");
        String path = alice + "/proj.git" + UPLOAD_ADVERTISEMENT;

        for (String authorization : new String[] {bearer(alice), basic(TestJws.forAgent(alice))}) {
            ResponseEntity<String> response = git(HttpMethod.GET, path, authorization);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType())
                    .isEqualTo(MediaType.parseMediaType("application/x-git-upload-pack-advertisement"));
        }
    }

    @Test
    void urlWithoutDotGitWorksToo() {
        String alice = unique("alice");
        createRepo(alice, "proj");

        assertThat(git(HttpMethod.GET, alice + "/proj" + UPLOAD_ADVERTISEMENT, bearer(alice)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void noAccessLooksExactlyLikeAMissingRepository() {
        String alice = unique("alice");
        String eve = unique("eve");
        createRepo(alice, "proj");

        ResponseEntity<String> hidden = git(HttpMethod.GET, alice + "/proj.git" + UPLOAD_ADVERTISEMENT, bearer(eve));
        ResponseEntity<String> missing = git(HttpMethod.GET, alice + "/nope.git" + UPLOAD_ADVERTISEMENT, bearer(eve));

        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertPlainText(hidden);
        assertThat(hidden.getBody().replace("proj", "nope")).isEqualTo(missing.getBody());
    }

    @Test
    void readerIsStoppedBeforeAnyPackIsReceived() {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "proj");
        grant(alice, "proj", bob, "READ");

        ResponseEntity<String> advertisement = git(HttpMethod.GET, alice + "/proj.git" + RECEIVE_ADVERTISEMENT,
                bearer(bob));
        ResponseEntity<String> pack = git(HttpMethod.POST, alice + "/proj.git/git-receive-pack", bearer(bob));

        for (ResponseEntity<String> response : new ResponseEntity[] {advertisement, pack}) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertPlainText(response);
            assertThat(response.getBody()).startsWith("insufficient_role: ")
                    .contains("PUT /v1/repos/" + alice + "/proj/grants/" + bob);
        }
        assertThat(git(HttpMethod.GET, alice + "/proj.git" + UPLOAD_ADVERTISEMENT, bearer(bob)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void proposerMayStartAPush() {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "proj");
        grant(alice, "proj", bob, "PROPOSE");

        ResponseEntity<String> response = git(HttpMethod.GET, alice + "/proj.git" + RECEIVE_ADVERTISEMENT,
                bearer(bob));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void dumbHttpIsNotServed() {
        String alice = unique("alice");
        createRepo(alice, "proj");

        for (String path : new String[] {"/HEAD", "/info/refs", "/objects/info/packs", "/config"}) {
            ResponseEntity<String> response = git(HttpMethod.GET, alice + "/proj.git" + path, bearer(alice));
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertPlainText(response);
            assertThat(response.getBody()).contains("smart HTTP");
        }
    }

    @Test
    void oversizedRepositoryStillAcceptsPushConnectionsSoBranchesCanBeDeleted() {
        String alice = unique("alice");
        createRepo(alice, "proj");
        long id = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "proj").orElseThrow().getId();
        tx.executeWithoutResult(s -> repos.updateSize(id, DataSize.ofGigabytes(1).toBytes() + 1));

        assertThat(git(HttpMethod.GET, alice + "/proj.git" + RECEIVE_ADVERTISEMENT, bearer(alice)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(git(HttpMethod.GET, alice + "/proj.git" + UPLOAD_ADVERTISEMENT, bearer(alice)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void deletedRepositoryIsGone() {
        String alice = unique("alice");
        createRepo(alice, "proj");
        call(HttpMethod.DELETE, "/v1/repos/" + alice + "/proj", alice, null, Map.class);

        assertThat(git(HttpMethod.GET, alice + "/proj.git" + UPLOAD_ADVERTISEMENT, bearer(alice)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
