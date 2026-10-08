package org.example.agenthub.git;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.example.agenthub.repo.NamePolicy;
import org.example.agenthub.repo.Repo;
import org.example.agenthub.repo.RepoRepository;
import org.example.agenthub.repo.RepoStorage;
import org.springframework.stereotype.Component;

/**
 * Maps {@code /git/{owner}/{name}[.git]} to a repository id. JGit opens only what {@link GitAccessFilter}
 * already authorized for this very request; anything else is "not found".
 */
@Component
public class RepoResolver implements RepositoryResolver<HttpServletRequest> {

    private static final String DOT_GIT = ".git";

    private final RepoRepository repos;
    private final RepoStorage storage;

    public RepoResolver(RepoRepository repos, RepoStorage storage) {
        this.repos = repos;
        this.storage = storage;
    }

    /** URLs work with and without the {@code .git} suffix. */
    public static String withoutDotGit(String name) {
        return name.endsWith(DOT_GIT) ? name.substring(0, name.length() - DOT_GIT.length()) : name;
    }

    public Optional<Repo> resolve(String owner, String name) {
        if (!NamePolicy.isValidHandle(owner) || !NamePolicy.isValidRepoName(name)) {
            return Optional.empty();
        }
        return repos.findByOwnerAndNameAndDeletedAtIsNull(owner, name);
    }

    @Override
    public Repository open(HttpServletRequest request, String name) throws RepositoryNotFoundException {
        GitRequestContext context = GitRequestContext.of(request);
        if (context == null) {
            throw new RepositoryNotFoundException(name);
        }
        try {
            return storage.open(context.repoId());
        } catch (IOException e) {
            throw new RepositoryNotFoundException(name, e);
        }
    }
}
