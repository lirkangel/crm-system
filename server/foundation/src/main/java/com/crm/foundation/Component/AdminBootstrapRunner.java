package com.crm.foundation.Component;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.DTO.CreateUserRequest;
import com.crm.foundation.Domain.Role;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.RoleRepository;
import com.crm.foundation.Repository.UserRepository;
import com.crm.foundation.Service.AuditService;
import com.crm.foundation.Service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Creates the first admin on a fresh server (T008c). Public self-registration is gone
 * (T008), so without this nobody could ever log in to an empty database.
 *
 * <p>Runs on startup only while no user holds the Flyway-seeded {@code admin} role and
 * all of {@code BOOTSTRAP_ADMIN_USERNAME} / {@code _EMAIL} / {@code _PASSWORD} are set.
 * Once any admin exists it never creates, promotes or overwrites anyone, so the
 * variables are harmless if left behind, but the operator is told to remove them.
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private static final String ADMIN_ROLE = "admin";
    private static final String VARS = "BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD";

    private final String username;
    private final String email;
    private final String password;
    private final UserService userService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuditService auditService;

    public AdminBootstrapRunner(
            @Value("${foundation.bootstrap-admin.username:}") String username,
            @Value("${foundation.bootstrap-admin.email:}") String email,
            @Value("${foundation.bootstrap-admin.password:}") String password,
            UserService userService,
            UserRepository userRepository,
            RoleRepository roleRepository,
            AuditService auditService) {
        this.username = username;
        this.email = email;
        this.password = password;
        this.userService = userService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    /** Package-visible for unit tests; production calls go through the transactional {@link #run}. */
    void bootstrap() {
        boolean anySet = StringUtils.hasText(username) || StringUtils.hasText(email) || StringUtils.hasText(password);
        if (!anySet) {
            if (!userRepository.existsByRoles_Code(ADMIN_ROLE)) {
                log.warn("No user holds the admin role, so nobody can administer this server. "
                    + "Set {} and restart to create the first admin.", VARS);
            }
            return;
        }
        if (!StringUtils.hasText(username) || !StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            log.warn("Admin bootstrap skipped: {} must all be set.", VARS);
            return;
        }

        // Row lock on the admin role serializes concurrent starts (e.g. two replicas),
        // so the check below and the insert after it cannot interleave.
        Role adminRole = roleRepository.lockByCode(ADMIN_ROLE)
            .orElseThrow(() -> new IllegalStateException("Flyway-seeded 'admin' role is missing"));

        if (userRepository.existsByRoles_Code(ADMIN_ROLE)) {
            log.warn("An admin already exists, so admin bootstrap did nothing. Remove {} from the environment.", VARS);
            return;
        }
        if (userRepository.existsByUsernameOrEmail(username, email)) {
            // Promoting an existing account from env vars would let anyone who can edit
            // the deployment take it over; make the operator pick an unused identity.
            log.warn("Admin bootstrap skipped: username or email is already used by an existing user. "
                + "Choose different values for {}.", VARS);
            return;
        }

        User admin = userService.createUser(new CreateUserRequest(username, email, password), null, null);
        admin.getRoles().add(adminRole);
        auditService.record(new AuditPayload(
            Instant.now(), null, null,
            null, "User", admin.getId(), "ADMIN_BOOTSTRAP", null, null, "WARN"));

        log.warn("Created bootstrap admin '{}'. Remove {} from the environment and its secret now.", username, VARS);
    }
}
