package com.crm.foundation.Controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.UUID;

/**
 * The one place a Foundation problem (RFC 7807) is assembled: {@code type} URN, status-name
 * {@code title}, legacy {@code code} extension and a {@code trace_id}. Used by {@link ErrorHandler}
 * and by servlet filters that answer before MVC runs.
 */
public final class Problems {

    public static final String TYPE_BAD_REQUEST = "urn:problem-type:bad-request";
    public static final String TYPE_RATE_LIMITED = "urn:problem-type:rate-limited";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String type, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("trace_id", UUID.randomUUID().toString());
        problem.setProperty("code", code);
        return problem;
    }
}
