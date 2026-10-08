package org.example.agenthub.auth;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, String> {

    List<Agent> findByHandleContainingAndHandleGreaterThanOrderByHandle(String q, String after, Pageable page);
}
