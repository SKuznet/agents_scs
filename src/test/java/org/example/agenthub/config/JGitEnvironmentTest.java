package org.example.agenthub.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.Test;

class JGitEnvironmentTest {

    @Test
    void jgitNeverReadsUserSystemOrJGitConfigFiles() throws Exception {
        JGitEnvironment.install();
        SystemReader reader = SystemReader.getInstance();

        assertThat(reader.openUserConfig(null, FS.DETECTED).getFile()).isNull();
        assertThat(reader.openSystemConfig(null, FS.DETECTED).getFile()).isNull();
        assertThat(reader.openJGitConfig(null, FS.DETECTED).getFile()).isNull();
        assertThat(reader.getUserConfig().getSections()).isEmpty();
        assertThat(reader.getSystemConfig().getSections()).isEmpty();
        assertThat(reader.getJGitConfig().getSections()).isEmpty();
    }

    @Test
    void installIsIdempotent() {
        JGitEnvironment.install();
        SystemReader first = SystemReader.getInstance();

        JGitEnvironment.install();

        assertThat(SystemReader.getInstance()).isSameAs(first);
    }
}
