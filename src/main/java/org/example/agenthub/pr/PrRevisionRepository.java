package org.example.agenthub.pr;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrRevisionRepository extends JpaRepository<PrRevision, PrRevision.Key> {

    List<PrRevision> findByPrIdOrderByRev(long prId);

    Optional<PrRevision> findByPrIdAndRev(long prId, int rev);
}
