package com.crm.hotel;

import com.crm.foundation.Plugin.PluginProblem;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IfMatchTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void matching_bare_or_quoted_number_passes() {
        assertThatCode(() -> IfMatch.requireVersion("3", 3L, String.class, ID)).doesNotThrowAnyException();
        assertThatCode(() -> IfMatch.requireVersion("\"3\"", 3L, String.class, ID)).doesNotThrowAnyException();
        assertThatCode(() -> IfMatch.requireVersion(" \"0\" ", 0L, String.class, ID)).doesNotThrowAnyException();
    }

    @Test
    void missing_blank_or_non_numeric_is_428() {
        for (String header : new String[] {null, "", "   ", "abc", "\"\"", "1.5", "W/\"3\""}) {
            assertThatThrownBy(() -> IfMatch.requireVersion(header, 3L, String.class, ID))
                .as("If-Match %s", header)
                .isInstanceOfSatisfying(PluginProblem.class, problem -> {
                    assertThat(problem.status()).isEqualTo(428);
                    assertThat(problem.code()).isEqualTo("HOTEL_VERSION_REQUIRED");
                });
        }
    }

    @Test
    void stale_or_unknown_version_is_an_optimistic_lock_failure() {
        assertThatThrownBy(() -> IfMatch.requireVersion("2", 3L, String.class, ID))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThatThrownBy(() -> IfMatch.requireVersion("\"4\"", 3L, String.class, ID))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThatThrownBy(() -> IfMatch.requireVersion("0", null, String.class, ID))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
