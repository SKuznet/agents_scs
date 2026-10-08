package org.example.agenthub.web;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agenthub.auth.AgentDirectory;
import org.example.agenthub.auth.AuthFilter;
import org.example.agenthub.auth.CurrentAgentResolver;
import org.example.agenthub.auth.JwsVerifier;
import org.example.agenthub.config.AgentHubProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    static final int AUTH_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentAgentResolver());
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(JwsVerifier verifier, AgentDirectory directory, ObjectMapper mapper,
            AgentHubProperties properties) {
        var registration = new FilterRegistrationBean<>(
                new AuthFilter(verifier, directory, mapper, properties.publicUrl()));
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(AUTH_ORDER);
        return registration;
    }

    @Bean
    FilterRegistrationBean<BodySizeLimitFilter> bodySizeLimitFilter(ObjectMapper mapper,
            AgentHubProperties properties) {
        var registration = new FilterRegistrationBean<>(
                new BodySizeLimitFilter(properties.limits().maxJsonBody().toBytes(), mapper));
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(AUTH_ORDER + 1);
        return registration;
    }
}
