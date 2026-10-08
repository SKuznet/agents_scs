package org.example.agenthub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agenthub.IntegrationTest;
import org.example.agenthub.repo.RepoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;

class EventLogTest extends IntegrationTest {

    @Autowired
    EventRepository events;

    @Autowired
    RepoRepository repos;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ObjectMapper mapper;

    List<Event> eventsOf(long repoId, int expected) {
        return await().atMost(Duration.ofSeconds(5))
                .until(() -> events.findByRepoIdOrderById(repoId), list -> list.size() >= expected);
    }

    JsonNode payload(Event event) throws Exception {
        return mapper.readTree(event.getPayload());
    }

    @Test
    void repositoryLifecycleAndGrantChangesAreRecordedInOrder() throws Exception {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "proj");
        long id = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "proj").orElseThrow().getId();
        grant(alice, "proj", bob, "READ");
        grant(alice, "proj", bob, "READ");
        grant(alice, "proj", bob, "WRITE");
        call(HttpMethod.DELETE, "/v1/repos/" + alice + "/proj/grants/" + bob, alice, null, Void.class);
        call(HttpMethod.DELETE, "/v1/repos/" + alice + "/proj", alice, null, Void.class);

        List<Event> log = eventsOf(id, 5);

        assertThat(log).extracting(Event::getType)
                .containsExactly("repo.created", "grant.changed", "grant.changed", "grant.changed", "repo.deleted");
        assertThat(log).extracting(Event::getActor).containsOnly(alice);
        assertThat(log).extracting(Event::getId).isSorted();
        assertThat(payload(log.get(0)).get("repo").asText()).isEqualTo(alice + "/proj");
        assertThat(payload(log.get(2)).get("agent").asText()).isEqualTo(bob);
        assertThat(payload(log.get(2)).get("role").asText()).isEqualTo("WRITE");
        assertThat(payload(log.get(2)).get("previous").asText()).isEqualTo("READ");
        assertThat(payload(log.get(3)).get("role").isNull()).isTrue();
        assertThat(payload(log.get(4)).get("repo").asText()).isEqualTo(alice + "/proj");
        assertThat(log.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    void eventsOfRolledBackTransactionsAreNeverWritten() {
        long repoId = -System.nanoTime();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            publisher.publishEvent(new DomainEvent("repo.created", repoId, null, "ghost-writer", Map.of()));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(status -> publisher.publishEvent(
                new DomainEvent("repo.deleted", repoId, null, "ghost-writer", Map.of())));

        assertThat(eventsOf(repoId, 1)).extracting(Event::getType).containsExactly("repo.deleted");
    }
}
