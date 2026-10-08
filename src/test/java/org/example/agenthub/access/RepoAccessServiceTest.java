package org.example.agenthub.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoGrant;
import org.example.agenthub.repo.RepoGrantRepository;
import org.example.agenthub.repo.RepoRepository;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.junit.jupiter.api.Test;

class RepoAccessServiceTest {

    final RepoRepository repos = mock(RepoRepository.class);
    final RepoGrantRepository grants = mock(RepoGrantRepository.class);
    final RepoAccessService access = new RepoAccessService(repos, grants);

    final Repo privateRepo = repo(Visibility.PRIVATE);
    final Repo internalRepo = repo(Visibility.INTERNAL);

    static Repo repo(Visibility visibility) {
        return new Repo("alice", "proj", visibility, null, true);
    }

    @Test
    void ownerIsAdmin() {
        assertThat(RepoAccessService.effectiveRole("alice", privateRepo, Optional.empty())).contains(Role.ADMIN);
        assertThat(RepoAccessService.effectiveRole("alice", privateRepo, Optional.of(Role.READ))).contains(Role.ADMIN);
    }

    @Test
    void grantGivesItsRole() {
        assertThat(RepoAccessService.effectiveRole("bob", privateRepo, Optional.of(Role.PROPOSE)))
                .contains(Role.PROPOSE);
    }

    @Test
    void strangerHasNoAccessToPrivateRepository() {
        assertThat(RepoAccessService.effectiveRole("eve", privateRepo, Optional.empty())).isEmpty();
    }

    @Test
    void internalRepositoryGivesEveryoneRead() {
        assertThat(RepoAccessService.effectiveRole("eve", internalRepo, Optional.empty())).contains(Role.READ);
        assertThat(RepoAccessService.effectiveRole("bob", internalRepo, Optional.of(Role.WRITE))).contains(Role.WRITE);
    }

    @Test
    void rolesAreOrdered() {
        assertThat(Role.ADMIN.atLeast(Role.WRITE)).isTrue();
        assertThat(Role.WRITE.atLeast(Role.PROPOSE)).isTrue();
        assertThat(Role.PROPOSE.atLeast(Role.READ)).isTrue();
        assertThat(Role.READ.atLeast(Role.PROPOSE)).isFalse();
    }

    @Test
    void requireHidesRepositoriesWithoutAccess() {
        when(repos.findByOwnerAndNameAndDeletedAtIsNull("alice", "proj")).thenReturn(Optional.of(privateRepo));

        assertThatThrownBy(() -> access.require("eve", "alice", "proj", Role.READ))
                .isInstanceOfSatisfying(HintedException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> access.require("eve", "alice", "nope", Role.READ))
                .isInstanceOfSatisfying(HintedException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void requireExplainsMissingRoleAndWhomToAsk() {
        when(repos.findByOwnerAndNameAndDeletedAtIsNull("alice", "proj")).thenReturn(Optional.of(privateRepo));
        when(grants.findByRepoIdAndAgent(privateRepo.getId(), "bob"))
                .thenReturn(Optional.of(new RepoGrant(privateRepo.getId(), "bob", Role.READ, "alice", null)));

        assertThatThrownBy(() -> access.require("bob", "alice", "proj", Role.WRITE))
                .isInstanceOfSatisfying(HintedException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_ROLE);
                    assertThat(e.getMessage()).contains("WRITE").contains("READ");
                    assertThat(e.hint()).contains("alice").contains("PUT /v1/repos/alice/proj/grants/bob");
                });
    }

    @Test
    void requireReturnsRepositoryAndRole() {
        when(repos.findByOwnerAndNameAndDeletedAtIsNull("alice", "proj")).thenReturn(Optional.of(internalRepo));

        RepoAccess granted = access.require("eve", "alice", "proj", Role.READ);

        assertThat(granted.repo()).isSameAs(internalRepo);
        assertThat(granted.role()).isEqualTo(Role.READ);
    }
}
