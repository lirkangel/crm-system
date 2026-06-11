package com.crm.foundation.Controller;

import com.crm.foundation.Exception.AuthException;
import com.crm.foundation.Exception.BadRequestException;
import com.crm.foundation.Exception.NotFoundException;
import com.crm.foundation.Plugin.InvalidPluginPackageException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorHandlerTest {

    private final ErrorHandler handler = new ErrorHandler();

    private static void assertProblemBasics(ProblemDetail problem, int status, String typeUrn) {
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getType()).isEqualTo(URI.create(typeUrn));
        assertThat(problem.getProperties()).containsKey("trace_id");
        assertThat(problem.getProperties().get("trace_id")).asString().isNotBlank();
    }

    @Test
    void validation_problem_carries_field_details() throws NoSuchMethodException {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "loginRequest");
        binding.rejectValue(null, "NotBlank", "must not be blank");
        binding.addError(new org.springframework.validation.FieldError(
            "loginRequest", "username", "must not be blank"));
        MethodParameter parameter = new MethodParameter(
            getClass().getDeclaredMethod("sampleMethod", String.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, binding);

        ProblemDetail problem = handler.handleValidation(ex);

        assertProblemBasics(problem, 400, "urn:problem-type:validation");
        assertThat(problem.getProperties().get("code")).isEqualTo("VALIDATION_ERROR");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> errors = (List<Map<String, String>>) problem.getProperties().get("errors");
        assertThat(errors).anySatisfy(error -> {
            assertThat(error.get("field")).isEqualTo("username");
            assertThat(error.get("message")).isEqualTo("must not be blank");
        });
    }

    @Test
    void auth_problem_is_401_with_exception_code() {
        ProblemDetail problem = handler.handleAuthException(
            new AuthException("AUTH_BAD_CREDENTIALS", "Invalid username or password"));

        assertProblemBasics(problem, 401, "urn:problem-type:unauthorized");
        assertThat(problem.getProperties().get("code")).isEqualTo("AUTH_BAD_CREDENTIALS");
        assertThat(problem.getDetail()).isEqualTo("Invalid username or password");
    }

    @Test
    void forbidden_problem_is_403() {
        ProblemDetail problem = handler.handleAccessDenied(new AccessDeniedException("Access Denied"));

        assertProblemBasics(problem, 403, "urn:problem-type:forbidden");
        assertThat(problem.getProperties().get("code")).isEqualTo("FORBIDDEN");
    }

    @Test
    void not_found_problem_is_404() {
        ProblemDetail problem = handler.handleNotFound(new NotFoundException("Plugin not found: x"));

        assertProblemBasics(problem, 404, "urn:problem-type:not-found");
        assertThat(problem.getDetail()).isEqualTo("Plugin not found: x");
    }

    @Test
    void bad_request_problem_is_400() {
        ProblemDetail problem = handler.handleBadRequest(new BadRequestException("Plugin already registered"));

        assertProblemBasics(problem, 400, "urn:problem-type:bad-request");
        assertThat(problem.getDetail()).isEqualTo("Plugin already registered");
    }

    @Test
    void illegal_argument_problem_is_400() {
        ProblemDetail problem = handler.handleIllegalArgument(new IllegalArgumentException("nope"));

        assertProblemBasics(problem, 400, "urn:problem-type:bad-request");
    }

    @Test
    void conflict_problem_is_409() {
        ProblemDetail problem = handler.handleConflict(
            new OptimisticLockingFailureException("version mismatch"));

        assertProblemBasics(problem, 409, "urn:problem-type:conflict");
        assertThat(problem.getProperties().get("code")).isEqualTo("CONFLICT");
    }

    @Test
    void plugin_load_problem_is_422() {
        ProblemDetail problem = handler.handlePluginPackage(
            new InvalidPluginPackageException("unsafe ZIP entry path"));

        assertProblemBasics(problem, 422, "urn:problem-type:plugin-load");
        assertThat(problem.getDetail()).contains("unsafe ZIP entry path");
    }

    @Test
    void each_problem_gets_a_distinct_trace_id() {
        ProblemDetail first = handler.handleNotFound(new NotFoundException("x"));
        ProblemDetail second = handler.handleNotFound(new NotFoundException("x"));

        assertThat(first.getProperties().get("trace_id"))
            .isNotEqualTo(second.getProperties().get("trace_id"));
    }

    @SuppressWarnings("unused")
    private void sampleMethod(String arg) {
    }
}
