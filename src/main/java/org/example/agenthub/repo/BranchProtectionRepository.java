package org.example.agenthub.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BranchProtectionRepository extends JpaRepository<BranchProtection, BranchProtection.Key> {

    List<BranchProtection> findByRepoId(Long repoId);

    @Modifying
    @Query("delete from BranchProtection p where p.repoId = :repoId")
    void deleteAllOfRepo(Long repoId);
}
