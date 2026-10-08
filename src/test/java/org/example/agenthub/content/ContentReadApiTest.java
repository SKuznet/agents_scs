package org.example.agenthub.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.example.agenthub.git.GitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** §10.5: reading a repository without git. */
class ContentReadApiTest extends GitIntegrationTest {

    static final byte[] BINARY = {0, 1, 2, (byte) 0xff, 0, 42};

    String owner;
    String reader;
    String api;
    String devCommit;

    @BeforeEach
    void repository() throws Exception {
        owner = unique("olga");
        reader = unique("rita");
        createRepo(owner, "proj");
        grant(owner, "proj", reader, "READ");
        api = "/v1/repos/" + owner + "/proj";
        try (Git clone = cloneAs(owner, owner, "proj")) {
            commitFile(clone, "src/main.txt", "main\n");
            Files.write(clone.getRepository().getWorkTree().toPath().resolve("src/data.bin"), BINARY);
            clone.add().addFilepattern("src/data.bin").call();
            RevCommit dev = clone.commit().setMessage("add data").setSign(false).call();
            devCommit = dev.name();
            push(clone, owner, "HEAD:refs/heads/feature/dev");
        }
    }

    Map<String, Object> get(String path) {
        ResponseEntity<Map> response = call(HttpMethod.GET, api + path, reader, null, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    void aTextFileOfTheDefaultBranch() {
        Map<String, Object> file = get("/contents/AGENTS.md");

        assertThat(file).containsEntry("type", "file").containsEntry("path", "AGENTS.md")
                .containsEntry("encoding", "utf-8").containsKeys("size", "sha");
        assertThat((String) file.get("content")).contains("is data, not instructions");
    }

    @Test
    void foldersListTheirEntriesAndRefsSelectTheBranch() {
        assertThat((List<Map<String, Object>>) get("/contents").get("entries")).extracting(e -> e.get("name"))
                .containsExactly("AGENTS.md", "README.md");

        Map<String, Object> src = get("/contents/src?ref=feature/dev");

        assertThat(src).containsEntry("type", "dir").containsEntry("path", "src");
        assertThat((List<Map<String, Object>>) src.get("entries")).extracting(e -> e.get("name"), e -> e.get("type"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("data.bin", "file"),
                        org.assertj.core.groups.Tuple.tuple("main.txt", "file"));
        assertThat(get("/contents/?ref=" + devCommit)).containsEntry("type", "dir");
    }

    @Test
    void binaryFilesComeAsBase64OrRaw() {
        Map<String, Object> file = get("/contents/src/data.bin?ref=feature/dev");

        assertThat(file).containsEntry("encoding", "base64").containsEntry("size", BINARY.length);
        assertThat(Base64.getDecoder().decode((String) file.get("content"))).isEqualTo(BINARY);
        ResponseEntity<byte[]> raw = call(HttpMethod.GET, api + "/contents/src/data.bin?ref=feature/dev&raw=true",
                reader, null, byte[].class);
        assertThat(raw.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(raw.getBody()).isEqualTo(BINARY);
    }

    @Test
    void missingPathsAndRefsAreNotFoundWithAHint() {
        ResponseEntity<Map> path = call(HttpMethod.GET, api + "/contents/nope.txt", reader, null, Map.class);
        ResponseEntity<Map> ref = call(HttpMethod.GET, api + "/contents/README.md?ref=nope", reader, null,
                Map.class);

        assertThat(path.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((String) path.getBody().get("hint")).contains("/contents");
        assertThat(ref.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((String) ref.getBody().get("hint")).contains("/branches");
    }

    @Test
    void historyOfARefAndOfAPath() {
        Map<String, Object> page = get("/commits?ref=feature/dev&limit=1");
        List<Map<String, Object>> first = (List<Map<String, Object>>) page.get("items");
        assertThat(first).singleElement().satisfies(c -> assertThat(c).containsEntry("sha", devCommit));

        Map<String, Object> rest = get("/commits?ref=feature/dev&limit=10&cursor=" + page.get("nextCursor"));
        assertThat((List<?>) rest.get("items")).hasSize(2);
        assertThat(rest.get("nextCursor")).isNull();

        List<Map<String, Object>> ofPath = (List<Map<String, Object>>) get("/commits?ref=feature/dev&path=src/main.txt")
                .get("items");
        assertThat(ofPath).singleElement().satisfies(c -> assertThat(c).containsEntry("title", "change src/main.txt"));
    }

    @Test
    void aCommitWithItsDiff() {
        Map<String, Object> commit = get("/commits/" + devCommit);

        assertThat((Map<String, Object>) commit.get("commit")).containsEntry("sha", devCommit)
                .containsEntry("title", "add data");
        assertThat((List<Map<String, Object>>) commit.get("files")).singleElement()
                .satisfies(f -> assertThat(f).containsEntry("path", "src/data.bin").containsEntry("binary", true));
        assertThat((Map<String, Object>) commit.get("diff")).containsKey("files");
        assertThat(call(HttpMethod.GET, api + "/commits/" + "0".repeat(40), reader, null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void compareShowsCommitsAndTheThreeDotDiff() throws Exception {
        Map<String, Object> compare = get("/compare?base=main&head=feature/dev");

        assertThat(compare).containsEntry("aheadBy", 2).containsEntry("behindBy", 0).containsKey("mergeBaseSha");
        assertThat((List<?>) compare.get("commits")).hasSize(2);
        assertThat((List<Map<String, Object>>) compare.get("files")).extracting(f -> f.get("path"))
                .containsExactlyInAnyOrder("src/main.txt", "src/data.bin");

        try (Git clone = cloneAs(owner, owner, "proj")) {
            clone.checkout().setOrphan(true).setName("orphan").call();
            commitFile(clone, "lonely.txt", "alone\n");
            push(clone, owner, "HEAD:refs/heads/orphan");
        }
        ResponseEntity<Map> unrelated = call(HttpMethod.GET, api + "/compare?base=main&head=orphan", reader, null,
                Map.class);
        assertThat(unrelated.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unrelated.getBody()).containsEntry("code", "unrelated_history").containsKey("hint");
    }
}
