package org.example.agenthub;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the whole app with in-memory H2 and a temp repo store. The port is picked up front (instead of
 * RANDOM_PORT) so that {@code agenthub.public-url} points at the running server, as git's
 * {@code http.<url>.extraHeader} from {@code /v1/setup} must match the real URL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(IntegrationTest.TestClockConfig.class)
public abstract class IntegrationTest {

    protected static final Path STORAGE_ROOT;
    protected static final int PORT;
    private static final Executor CLIENTS = Executors.newVirtualThreadPerTaskExecutor();

    static {
        try {
            STORAGE_ROOT = Files.createTempDirectory("agenthub-test");
            try (ServerSocket socket = new ServerSocket(0)) {
                PORT = socket.getLocalPort();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("agenthub.public-url", IntegrationTest::baseUrl);
        registry.add("agenthub.jws-secret", () -> TestJws.SECRET);
        registry.add("agenthub.storage-root", STORAGE_ROOT::toString);
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:agenthub;DB_CLOSE_DELAY=-1");
        registry.add("agenthub.gc.interval", () -> "0s");
        registry.add("agenthub.sweep-interval", () -> "0s");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected MutableClock clock;

    protected static String baseUrl() {
        return "http://localhost:" + PORT;
    }

    /** Unique handle/name so tests sharing one context never collide. */
    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected <T> ResponseEntity<T> call(HttpMethod method, String path, String agent, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (agent != null) {
            headers.setBearerAuth(TestJws.forAgent(agent));
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    /** For calls that block, such as long-polls: virtual threads, whatever the size of the common pool. */
    protected <T> CompletableFuture<ResponseEntity<T>> callAsync(HttpMethod method, String path, String agent,
            Object body, Class<T> type) {
        return CompletableFuture.supplyAsync(() -> call(method, path, agent, body, type), CLIENTS);
    }

    protected Map<String, Object> createRepo(String owner, String name) {
        return createRepo(owner, Map.of("name", name));
    }

    protected Map<String, Object> createRepo(String owner, Map<String, ?> request) {
        ResponseEntity<Map> response = call(HttpMethod.POST, "/v1/repos", owner, request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    protected void grant(String owner, String repo, String agent, String role) {
        ResponseEntity<Map> response = call(HttpMethod.PUT, "/v1/repos/" + owner + "/" + repo + "/grants/" + agent,
                owner, Map.of("role", role), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    protected String gitUrl(String owner, String repo) {
        return baseUrl() + "/git/" + owner + "/" + repo + ".git";
    }
}
