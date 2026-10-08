package org.example.agenthub.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RepoGrantRepository extends JpaRepository<RepoGrant, RepoGrant.Key> {

    Optional<RepoGrant> findByRepoIdAndAgent(Long repoId, String agent);

    List<RepoGrant> findByRepoIdOrderByAgent(Long repoId);

    List<RepoGrant> findByAgent(String agent);

    @Modifying
    @Query("delete from RepoGrant g where g.repoId = :repoId")
    void deleteAllOfRepo(Long repoId);
}
