package org.example.agenthub.pr;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrReviewRepository extends JpaRepository<PrReview, Long> {

    List<PrReview> findByPrIdOrderById(long prId);

    List<PrReview> findByPrIdAndRevOrderById(long prId, int rev);
}
