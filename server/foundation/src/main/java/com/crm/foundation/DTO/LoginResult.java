package com.crm.foundation.DTO;

import com.crm.foundation.Domain.User;

public sealed interface LoginResult permits LoginResult.Success, LoginResult.Failure {

    record Success(User user) implements LoginResult {}

    record Failure(FailureReason reason) implements LoginResult {}
}
