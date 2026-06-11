package com.crm.foundation.Controller;

import com.crm.foundation.Exception.AuthException;
import com.crm.foundation.Exception.BadRequestException;
import com.crm.foundation.Exception.NotFoundException;
import com.crm.foundation.Plugin.InvalidPluginPackageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RFC 7807 error contract (T016): every error is
 * {@code application/problem+json} with a stable {@code type} URN, a
 * {@code trace_id} for log correlation, and a legacy {@code code} extension
 * (the frontend client reads it). Validation problems carry per-field details.
 */
@RestControllerAdvice
public class ErrorHandler {

    private final Logger log = LoggerFactory.getLogger(getClass());

    static final String TYPE_VALIDATION = "urn:problem-type:validation";
    static final String TYPE_UNAUTHORIZED = "urn:problem-type:unauthorized";
    static final String TYPE_FORBIDDEN = "urn:problem-type:forbidden";
    static final String TYPE_NOT_FOUND = "urn:problem-type:not-found";
    static final String TYPE_BAD_REQUEST = "urn:problem-type:bad-request";
    static final String TYPE_CONFLICT = "urn:problem-type:conflict";
    static final String TYPE_PLUGIN_LOAD = "urn:problem-type:plugin-load";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of(
                "field", fe.getField(),
                "message", String.valueOf(fe.getDefaultMessage())))
            .toList();
        ProblemDetail problem = problem(
            HttpStatus.BAD_REQUEST, TYPE_VALIDATION, "VALIDATION_ERROR", "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(AuthException.class)
    public ProblemDetail handleAuthException(AuthException ex) {
        return problem(HttpStatus.UNAUTHORIZED, TYPE_UNAUTHORIZED, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, TYPE_FORBIDDEN, "FORBIDDEN", "Permission denied");
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadRequest(BadRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleConflict(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "CONFLICT",
            "The resource was modified concurrently; reload and retry");
    }

    @ExceptionHandler(InvalidPluginPackageException.class)
    public ProblemDetail handlePluginPackage(InvalidPluginPackageException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, TYPE_PLUGIN_LOAD, "PLUGIN_LOAD_FAILED", ex.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String type, String code, String detail) {
        String traceId = UUID.randomUUID().toString();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("trace_id", traceId);
        problem.setProperty("code", code);
        log.debug("Returning HTTP {} problem {} trace_id={} : {}", status.value(), type, traceId, detail);
        return problem;
    }
}
