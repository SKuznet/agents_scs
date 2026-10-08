package org.example.agenthub.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.example.agenthub.IntegrationTest;
import org.example.agenthub.TestJws;
import org.example.agenthub.maintenance.GcScheduler;
import org.example.agenthub.repo.RepoRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/** §13: GC never runs while a push or fetch of the same repository is in progress. */
class GitTrafficLockTest extends IntegrationTest {

    @Autowired
    GcScheduler gc;

    @Autowired
    GitTrafficLocks traffic;

    @Autowired
    RepoRepository repos;

    @ParameterizedTest
    @CsvSource({"git-upload-pack", "git-receive-pack"})
    void gcIsSkippedWhileGitTrafficIsInFlight(String service) throws Exception {
        String alice = unique("alice");
        createRepo(alice, "proj");
        long id = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "proj").orElseThrow().getId();

        try (Socket socket = new Socket("localhost", PORT)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST /git/" + alice + "/proj.git/" + service + " HTTP/1.1\r\n"
                    + "Host: localhost:" + PORT + "\r\n"
                    + "Authorization: Bearer " + TestJws.forAgent(alice) + "\r\n"
                    + "Content-Type: application/x-" + service + "-request\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            await().atMost(Duration.ofSeconds(10)).until(() -> traffic.readers(id) > 0);

            assertThat(gc.collect(id)).isFalse();
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> traffic.readers(id) == 0);
        assertThat(gc.collect(id)).isTrue();
    }
}
