package org.example.agenthub.pr;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PrReviewerRepository extends JpaRepository<PrReviewer, PrReviewer.Key> {

    List<PrReviewer> findByPrIdOrderByAgent(long prId);

    @Modifying
    @Query("delete from PrReviewer r where r.prId = :prId")
    void deleteAllOfPr(long prId);
}
