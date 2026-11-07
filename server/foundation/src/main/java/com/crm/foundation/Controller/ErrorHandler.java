package com.crm.foundation.Controller;

import com.crm.foundation.Exception.AuthException;
import com.crm.foundation.Exception.BadRequestException;
import com.crm.foundation.Exception.NotFoundException;
import com.crm.foundation.Plugin.InvalidPluginPackageException;
import com.crm.foundation.Plugin.PluginProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
    static final String TYPE_PLUGIN = "urn:problem-type:plugin";
    static final String TYPE_REQUEST_REJECTED = "urn:problem-type:request-rejected";
    static final String TYPE_INTERNAL = "urn:problem-type:internal";

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

    /**
     * No controller matched (e.g. the route of a plugin that isn't running):
     * a 404 problem like every other error, rather than Spring Boot's
     * error-page JSON.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ProblemDetail handleNoRoute(Exception ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "NOT_FOUND", "No endpoint matches this request");
    }

    /** Unreadable body (malformed JSON) or a value that doesn't convert (e.g. a bad UUID path variable). */
    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    public ProblemDetail handleMalformedRequest(Exception ex) {
        String detail = ex instanceof TypeMismatchException mismatch && mismatch.getPropertyName() != null
            ? "Invalid value for '" + mismatch.getPropertyName() + "'"
            : "Malformed request body";
        return problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST, "BAD_REQUEST", detail);
    }

    /**
     * Spring MVC's own request rejections (missing header/parameter, wrong
     * method, unsupported content type) keep their status and headers
     * (e.g. {@code Allow}) as a problem with the status name as {@code code}.
     */
    @ExceptionHandler({
        ServletRequestBindingException.class,
        HttpRequestMethodNotSupportedException.class,
        HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ProblemDetail> handleRejectedRequest(Exception ex) {
        ErrorResponse response = (ErrorResponse) ex;
        HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
        String type = typeFor(status);
        String detail = response.getBody().getDetail() != null ? response.getBody().getDetail() : ex.getMessage();
        return ResponseEntity.status(status)
            .headers(response.getHeaders())
            .body(problem(status, type, status.name(), detail));
    }

    /** Multipart upload over {@code spring.servlet.multipart.max-file-size}/{@code max-request-size}. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem(
            HttpStatus.PAYLOAD_TOO_LARGE, TYPE_REQUEST_REJECTED, "PAYLOAD_TOO_LARGE", "Upload is too large"));
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadRequest(BadRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        // The message comes from arbitrary (library) code: log it, never echo it to the client
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST, "BAD_REQUEST", "Invalid request");
        log.debug("IllegalArgumentException, trace_id={}: {}", problem.getProperties().get("trace_id"), ex.getMessage());
        return problem;
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

    @ExceptionHandler(PluginProblem.class)
    public ProblemDetail handlePluginProblem(PluginProblem ex) {
        return problem(HttpStatus.valueOf(ex.status()), TYPE_PLUGIN, ex.code(), ex.getMessage());
    }

    /**
     * Everything without a more specific handler. Exceptions that carry their
     * own status ({@link ErrorResponse} such as {@code ResponseStatusException},
     * or {@link ResponseStatus}-annotated ones) keep it, with their declared
     * reason; anything else is a generic 500 whose detail never reveals the
     * exception — it is logged here with the problem's {@code trace_id}.
     * Authentication failures go back to Spring Security (401).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof AuthenticationException || ex instanceof AsyncRequestNotUsableException) {
            throw ex;
        }
        if (ex instanceof ErrorResponse response && HttpStatus.resolve(response.getStatusCode().value()) != null) {
            HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
            String detail = response.getBody().getDetail() != null
                ? response.getBody().getDetail()
                : status.getReasonPhrase();
            return ResponseEntity.status(status)
                .headers(response.getHeaders())
                .body(problem(status, typeFor(status), status.name(), detail));
        }
        ResponseStatus declared = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        if (declared != null) {
            HttpStatus status = declared.code();
            String detail = declared.reason().isEmpty() ? status.getReasonPhrase() : declared.reason();
            return ResponseEntity.status(status).body(problem(status, typeFor(status), status.name(), detail));
        }
        ProblemDetail problem = problem(
            HttpStatus.INTERNAL_SERVER_ERROR, TYPE_INTERNAL, "INTERNAL_ERROR", "An unexpected error occurred");
        log.error("Unhandled exception, trace_id={}", problem.getProperties().get("trace_id"), ex);
        return ResponseEntity.internalServerError().body(problem);
    }

    private static String typeFor(HttpStatus status) {
        if (status == HttpStatus.BAD_REQUEST) {
            return TYPE_BAD_REQUEST;
        }
        return status.is5xxServerError() ? TYPE_INTERNAL : TYPE_REQUEST_REJECTED;
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
