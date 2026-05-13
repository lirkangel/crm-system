package com.crm.foundation.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class LoginRequest {
    @NotNull
    String username;
    @NotNull
    String password;

}
