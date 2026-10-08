package org.example.agenthub.repo;

import java.util.List;
import java.util.Optional;

import org.example.agenthub.access.Visibility;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RepoRepository extends JpaRepository<Repo, Long> {

    Optional<Repo> findByOwnerAndNameAndDeletedAtIsNull(String owner, String name);

    long countByOwnerAndDeletedAtIsNull(String owner);

    List<Repo> findByOwnerAndDeletedAtIsNullOrderByName(String owner);

    List<Repo> findByIdInAndDeletedAtIsNull(List<Long> ids);

    @Query("select r.id from Repo r where r.deletedAt is null order by r.id")
    List<Long> findLiveIds();

    @Query("select r.id from Repo r where r.deletedAt is not null order by r.id")
    List<Long> findDeletedIds();

    @Query("""
            select r from Repo r
            where r.deletedAt is null and r.id > :after and (:owner is null or r.owner = :owner)
              and (r.owner = :agent or r.visibility = :internal
                   or exists (select g from RepoGrant g where g.repoId = r.id and g.agent = :agent))
            order by r.id""")
    List<Repo> findAccessible(String agent, String owner, Visibility internal, long after, Pageable page);

    @Query("""
            select r from Repo r
            where r.deletedAt is null and r.id > :after and (:owner is null or r.owner = :owner)
              and exists (select g from RepoGrant g where g.repoId = r.id and g.agent = :agent)
            order by r.id""")
    List<Repo> findSharedWith(String agent, String owner, long after, Pageable page);

    @Query("""
            select count(r) from Repo r
            where r.deletedAt is null and r.visibility = :internal and r.owner <> :agent
              and not exists (select g from RepoGrant g where g.repoId = r.id and g.agent = :agent)""")
    long countVisibleOnlyAsInternal(String agent, Visibility internal);

    @Modifying
    @Query("update Repo r set r.sizeBytes = :size where r.id = :id")
    void updateSize(long id, long size);
}
