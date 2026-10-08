package org.example.agenthub.auth;

import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the {@link AgentPrincipal} established by {@link AuthFilter} into controller methods. */
public class CurrentAgentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AgentPrincipal.class;
    }

    @Override
    public AgentPrincipal resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Object principal = request.getAttribute(AgentPrincipal.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (principal == null) {
            throw new HintedException(ErrorCode.UNAUTHORIZED, "no authenticated agent", null);
        }
        return (AgentPrincipal) principal;
    }
}
