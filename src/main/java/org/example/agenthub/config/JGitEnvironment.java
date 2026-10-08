package org.example.agenthub.config;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.springframework.stereotype.Component;

/**
 * Cuts JGit off the host environment: no ~/.gitconfig, no XDG/jgit config and no external {@code git}
 * process to locate the system config. Every setting a repository needs is written into its own config.
 */
@Component
public class JGitEnvironment {

    public JGitEnvironment() {
        install();
    }

    public static synchronized void install() {
        SystemReader current = SystemReader.getInstance();
        if (!(current instanceof IsolatedSystemReader)) {
            SystemReader.setInstance(new IsolatedSystemReader(current));
        }
    }

    static final class IsolatedSystemReader extends SystemReader.Delegate {

        IsolatedSystemReader(SystemReader delegate) {
            super(delegate);
        }

        @Override
        public FileBasedConfig openUserConfig(Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }

        @Override
        public FileBasedConfig openSystemConfig(Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }

        @Override
        public FileBasedConfig openJGitConfig(Config parent, FS fs) {
            return new EmptyConfig(parent, fs);
        }
    }

    private static final class EmptyConfig extends FileBasedConfig {

        EmptyConfig(Config parent, FS fs) {
            super(parent, null, fs);
        }

        @Override
        public void load() {
        }

        @Override
        public void save() {
        }

        @Override
        public boolean isOutdated() {
            return false;
        }
    }
}
