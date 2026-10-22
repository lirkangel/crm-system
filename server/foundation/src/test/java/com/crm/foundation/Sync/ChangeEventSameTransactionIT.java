package com.crm.foundation.Sync;

import com.crm.foundation.Repository.ChangeEventRepository;
import com.crm.foundation.Service.ChangeEventService;
import com.crm.foundation.config.TestContainersConfig;
import com.crm.foundation.support.DockerTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@EnabledIf(
    value = "com.crm.foundation.support.DockerTestSupport#dockerAvailable",
    disabledReason = "Docker not available for Testcontainers")
@SpringBootTest
@Import(TestContainersConfig.class)
class ChangeEventSameTransactionIT {

    @Autowired
    private ChangeEventService changeEventService;

    @Autowired
    private ChangeEventRepository changeEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void changeEvent_rolledBackWithOuterTransaction() {
        long before = changeEventRepository.count();

        try {
            transactionTemplate.execute(status -> {
                changeEventService.record(
                    "core", "User",
                    UUID.randomUUID(), 1L, "CREATE", Instant.now());
                status.setRollbackOnly();
                return null;
            });
        } catch (Exception ignored) {
        }

        assertThat(changeEventRepository.count()).isEqualTo(before);
    }

    @Test
    void changeEvent_committedWhenTransactionSucceeds() {
        long before = changeEventRepository.count();
        UUID id = UUID.randomUUID();

        transactionTemplate.execute(status -> {
            changeEventService.record("core", "User", id, 1L, "CREATE", Instant.now());
            return null;
        });

        assertThat(changeEventRepository.count()).isEqualTo(before + 1);
        changeEventRepository.findAll().stream()
            .filter(e -> id.equals(e.getEntityId()))
            .findFirst()
            .ifPresentOrElse(
                e -> assertThat(e.getOp()).isEqualTo("CREATE"),
                () -> { throw new AssertionError("change_event not found"); });
    }
}
