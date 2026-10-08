package org.example.agenthub.events;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByRepoIdOrderById(Long repoId);
}
