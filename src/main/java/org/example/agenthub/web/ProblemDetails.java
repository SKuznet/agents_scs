package org.example.agenthub.web;

import java.io.IOException;
import java.net.URI;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 7807 responses with the {@code code}/{@code hint} extensions of §14, for every error under /v1. */
@RestControllerAdvice
public class ProblemDetails extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetails.class);
    private static final String TYPE_PREFIX = "https://agenthub/errors/";

    public static ProblemDetail of(ErrorCode code, String detail, String hint) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        decorate(problem, code);
        if (hint != null) {
            problem.setProperty("hint", hint);
        }
        return problem;
    }

    public static ProblemDetail payloadTooLarge(long limit) {
        return of(ErrorCode.PAYLOAD_TOO_LARGE, "request body exceeds " + limit + " bytes",
                "send a smaller JSON body; push large content with git instead");
    }

    /** For servlet filters, which run outside Spring MVC exception handling. */
    public static void write(HttpServletResponse response, ObjectMapper mapper, ProblemDetail problem)
            throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(mapper.writeValueAsBytes(problem));
    }

    @ExceptionHandler(HintedException.class)
    ResponseEntity<ProblemDetail> hinted(HintedException e) {
        ProblemDetail problem = of(e.code(), e.getMessage(), e.hint());
        e.properties().forEach(problem::setProperty);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.code().status());
        if (e.code() == ErrorCode.BUSY) {
            response.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return response.body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.internalServerError().body(of(ErrorCode.INTERNAL_ERROR, "unexpected server error",
                "retry the request; if it keeps failing, report the time of the request to the operator"));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (NestedExceptionUtils.getRootCause(ex) instanceof PayloadTooLargeException tooLarge) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(payloadTooLarge(tooLarge.limit()));
        }
        return ResponseEntity.badRequest().body(of(ErrorCode.VALIDATION_FAILED, "request body is not valid JSON"
                + " for this endpoint", "send a JSON object with the documented fields; see GET /v1/setup"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .map(fields -> "invalid fields: " + fields)
                .orElse("invalid request");
        return ResponseEntity.badRequest().body(of(ErrorCode.VALIDATION_FAILED, detail, null));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && problem.getProperties() == null) {
            ErrorCode code = codeFor(statusCode);
            decorate(problem, code);
            problem.setProperty("hint", switch (code) {
                case NOT_FOUND -> "check the path; the API is described in GET /v1/setup";
                case METHOD_NOT_ALLOWED -> "use a method this path supports (see the Allow header and GET /v1/setup)";
                default -> "fix the request as the detail says; the API is described in GET /v1/setup";
            });
        }
        return response;
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            default -> status.is4xxClientError() ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
        };
    }

    private static void decorate(ProblemDetail problem, ErrorCode code) {
        problem.setType(URI.create(TYPE_PREFIX + code.code()));
        problem.setProperty("code", code.code());
    }
}
