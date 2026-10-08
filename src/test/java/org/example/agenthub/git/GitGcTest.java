package org.example.agenthub.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.example.agenthub.maintenance.GcScheduler;
import org.example.agenthub.repo.RepoStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link GcScheduler} run by hand, as in every test (§13): pack-refs, repack, refreshed size. */
class GitGcTest extends GitIntegrationTest {

    @Autowired
    GcScheduler gc;

    @Autowired
    RepoStorage storage;

    @Test
    void manualGcPacksRefsAndRefreshesTheSize() throws Exception {
        String alice = unique("alice");
        createRepo(alice, "proj");
        long id = repos.findByOwnerAndNameAndDeletedAtIsNull(alice, "proj").orElseThrow().getId();
        try (Git clone = cloneAs(alice, alice, "proj")) {
            for (int i = 0; i < 3; i++) {
                commitFile(clone, "f" + i + ".txt", "content " + i);
                assertThat(push(clone, alice, "HEAD:refs/heads/b" + i).getStatus())
                        .isEqualTo(RemoteRefUpdate.Status.OK);
            }
        }
        Path dir = STORAGE_ROOT.resolve(id + ".git");
        assertThat(dir.resolve("refs/heads/b0")).exists();

        await().until(() -> gc.collect(id));

        assertThat(dir.resolve("packed-refs")).exists();
        assertThat(dir.resolve("refs/heads/b0")).doesNotExist();
        try (Repository server = serverRepo(alice, "proj")) {
            assertThat(server.exactRef("refs/heads/b2")).isNotNull();
        }
        assertThat(repos.findById(id).orElseThrow().getSizeBytes()).isEqualTo(storage.sizeOf(id));
    }
}
