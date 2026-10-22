package com.crm.foundation.Controller;

import com.crm.foundation.Exception.AuthException;
import com.crm.foundation.Exception.BadRequestException;
import com.crm.foundation.Exception.NotFoundException;
import com.crm.foundation.Plugin.InvalidPluginPackageException;
import com.crm.foundation.Plugin.PluginProblem;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
    void unmatched_route_problem_is_404_not_found() {
        ProblemDetail problem = handler.handleNoRoute(
            new NoResourceFoundException(HttpMethod.GET, "api/v1/plugins/nope/ping"));

        assertProblemBasics(problem, 404, "urn:problem-type:not-found");
        assertThat(problem.getProperties().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void malformed_body_problem_is_400() {
        ProblemDetail problem = handler.handleMalformedRequest(
            new HttpMessageNotReadableException("JSON parse error", new MockHttpInputMessage(new byte[0])));

        assertProblemBasics(problem, 400, "urn:problem-type:bad-request");
        assertThat(problem.getProperties().get("code")).isEqualTo("BAD_REQUEST");
    }

    @Test
    void unconvertible_value_problem_is_400_naming_the_parameter() {
        TypeMismatchException mismatch = new TypeMismatchException("not-a-uuid", java.util.UUID.class);
        mismatch.initPropertyName("id");

        ProblemDetail problem = handler.handleMalformedRequest(mismatch);

        assertProblemBasics(problem, 400, "urn:problem-type:bad-request");
        assertThat(problem.getDetail()).isEqualTo("Invalid value for 'id'");
    }

    @Test
    void missing_header_problem_is_400() {
        ResponseEntity<ProblemDetail> response = handler.handleRejectedRequest(
            new ServletRequestBindingException("Required header 'If-Match' is not present"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertProblemBasics(response.getBody(), 400, "urn:problem-type:bad-request");
        assertThat(response.getBody().getProperties().get("code")).isEqualTo("BAD_REQUEST");
    }

    @Test
    void wrong_method_problem_is_405_with_allow_header() {
        ResponseEntity<ProblemDetail> response = handler.handleRejectedRequest(
            new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST")));

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.GET, HttpMethod.POST);
        assertProblemBasics(response.getBody(), 405, "urn:problem-type:request-rejected");
        assertThat(response.getBody().getProperties().get("code")).isEqualTo("METHOD_NOT_ALLOWED");
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
    void plugin_problem_maps_status_and_code() {
        ProblemDetail problem = handler.handlePluginProblem(
            new PluginProblem(409, "HOTEL_ROOM_UNAVAILABLE", "x"));

        assertProblemBasics(problem, 409, "urn:problem-type:plugin");
        assertThat(problem.getProperties().get("code")).isEqualTo("HOTEL_ROOM_UNAVAILABLE");
        assertThat(problem.getDetail()).isEqualTo("x");
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
