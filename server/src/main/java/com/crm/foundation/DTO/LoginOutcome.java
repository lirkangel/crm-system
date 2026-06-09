package com.crm.foundation.DTO;

/**
 * Sealed result type for {@code AuthService.login}. Keeps HTTP-layer error-code
 * decisions inside the service and lets the controller pattern-match without
 * knowing anything about {@code LoginResult.FailureReason}.
 */
public sealed interface LoginOutcome permits LoginOutcome.Ok, LoginOutcome.Fail {
    record Ok(AuthResponse response) implements LoginOutcome {}
    record Fail(String code, String message) implements LoginOutcome {}
}
