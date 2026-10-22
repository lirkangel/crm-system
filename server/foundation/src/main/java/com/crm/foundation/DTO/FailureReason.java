package com.crm.foundation.DTO;

/**
 * Why an authentication attempt failed. Mapped to HTTP error codes/messages
 * by {@code AuthServiceImpl.login}.
 */
public enum FailureReason {
    BAD_CREDENTIALS,
    ACCOUNT_LOCKED,
    ACCOUNT_DISABLED
}
