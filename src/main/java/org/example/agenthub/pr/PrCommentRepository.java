package org.example.agenthub.pr;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrCommentRepository extends JpaRepository<PrComment, Long> {

    List<PrComment> findByPrIdOrderById(long prId);
}
