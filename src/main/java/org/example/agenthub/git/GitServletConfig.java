package org.example.agenthub.git;

import jakarta.servlet.http.HttpServletRequest;

import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.http.server.resolver.AsIsFileService;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;
import org.example.agenthub.access.RepoAccessService;
import org.example.agenthub.auth.AgentDirectory;
import org.example.agenthub.auth.JwsVerifier;
import org.example.agenthub.config.AgentHubProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Smart HTTP on {@code /git/*} (§6.5): dumb HTTP off, everything behind {@link GitAccessFilter}. */
@Configuration(proxyBeanMethods = false)
public class GitServletConfig {

    @Bean
    ServletRegistrationBean<GitServlet> gitServlet(RepoResolver resolver, HubReceivePackFactory receivePackFactory) {
        GitServlet servlet = new GitServlet();
        servlet.setRepositoryResolver(resolver);
        servlet.setAsIsFileService(AsIsFileService.DISABLED);
        servlet.setUploadPackFactory(uploadPackFactory());
        servlet.setReceivePackFactory(receivePackFactory);
        ServletRegistrationBean<GitServlet> registration = new ServletRegistrationBean<>(servlet, "/git/*");
        registration.setName("git");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    FilterRegistrationBean<GitAccessFilter> gitAccessFilter(JwsVerifier verifier, AgentDirectory directory,
            RepoResolver resolver, RepoAccessService access, GitTrafficLocks traffic, AgentHubProperties properties) {
        var registration = new FilterRegistrationBean<>(new GitAccessFilter(verifier, directory, resolver, access,
                traffic, properties.limits().maxRepoSize(), properties.publicUrl()));
        registration.addUrlPatterns("/git/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }

    private static UploadPackFactory<HttpServletRequest> uploadPackFactory() {
        return (request, db) -> {
            if (GitRequestContext.of(request) == null) {
                throw new ServiceNotAuthorizedException();
            }
            return new UploadPack(db);
        };
    }
}
