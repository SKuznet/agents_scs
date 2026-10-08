package org.example.agenthub.pr;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.example.agenthub.repo.Repo;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {

    @Query("select p from PullRequest p where p.repoId = :repoId and p.author = :author and p.openName = :name")
    Optional<PullRequest> findOpen(long repoId, String author, String name);

    Optional<PullRequest> findByRepoIdAndNumber(long repoId, int number);

    default PullRequest require(Repo repo, int number) {
        return findByRepoIdAndNumber(repo.getId(), number).orElseThrow(() -> new HintedException(
                ErrorCode.NOT_FOUND, "pull request #" + number + " not found in " + repo.getFullName(),
                "list pull requests: GET /v1/repos/" + repo.getFullName() + "/pulls"));
    }

    Optional<PullRequest> findFirstByRepoIdAndAuthorAndNameAndStateOrderByNumberDesc(long repoId, String author,
            String name, PrState state);

    long countByRepoIdAndAuthorAndState(long repoId, String author, PrState state);

    long countByRepoIdAndAuthorAndCreatedAtAfter(long repoId, String author, Instant since);

    List<PullRequest> findByRepoIdAndStateAndTargetBranch(long repoId, PrState state, String targetBranch);

    List<PullRequest> findByRepoId(long repoId);

    List<PullRequest> findByRepoIdInAndStateAndDraftFalse(Collection<Long> repoIds, PrState state);

    List<PullRequest> findByAuthorAndState(String author, PrState state);

    List<PullRequest> findByState(PrState state);

    List<PullRequest> findByRepoIdAndMergingCommitShaIsNotNull(long repoId);

    /** Compare-and-set of §7.4: only an open PR whose head is still the one that was checked. */
    @Modifying
    @Query("""
            update PullRequest p set p.mergeable = :status, p.mergeableTargetSha = :tip,
                p.conflictPaths = :conflicts, p.mergeableError = :error, p.version = p.version + 1
            where p.id = :id and p.headSha = :head and p.state = org.example.agenthub.pr.PrState.OPEN""")
    int recordMergeability(long id, String head, Mergeable status, String tip, String conflicts, String error);

    @Query("""
            select p from PullRequest p
            where p.repoId = :repoId and p.number < :before
              and (:state is null or p.state = :state)
              and (:author is null or p.author = :author)
              and (:target is null or p.targetBranch = :target)
            order by p.number desc""")
    List<PullRequest> search(long repoId, PrState state, String author, String target, int before, Pageable page);
}
