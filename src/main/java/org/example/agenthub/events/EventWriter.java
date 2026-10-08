package org.example.agenthub.events;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The single event writer of §13: events are stored after commit by one thread, so ids grow in the order in
 * which events become visible. An event may be lost if the process dies; consumers recompute from state.
 */
@Component
public class EventWriter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EventWriter.class);

    private final EventRepository events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "agenthub-event-writer");
        thread.setDaemon(true);
        return thread;
    });

    public EventWriter(EventRepository events, ObjectMapper mapper, Clock clock) {
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void on(DomainEvent event) {
        Instant at = clock.instant();
        writer.execute(() -> write(event, at));
    }

    private void write(DomainEvent event, Instant at) {
        try {
            events.save(new Event(event.repoId(), event.prId(), event.type(), event.actor(),
                    mapper.writeValueAsString(event.payload()), at));
        } catch (Exception e) {
            log.warn("event {} of repository {} was not recorded", event.type(), event.repoId(), e);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        writer.shutdown();
        writer.awaitTermination(5, TimeUnit.SECONDS);
    }
}
