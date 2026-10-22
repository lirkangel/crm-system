package com.crm.foundation.Plugin;

import com.crm.foundation.Domain.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** {@link CurrentUser} backed by the {@link User} principal that the JWT filter installs. */
@Component
public class SecurityContextCurrentUser implements CurrentUser {

    @Override
    public Optional<UUID> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            return Optional.ofNullable(user.getId());
        }
        return Optional.empty();
    }
}
