package com.crm.foundation.Component;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards T010: {@code TimestampingListener} must stay limited to timestamp/defaulting.
 * Audit-event writes belong in explicit services (e.g. {@code AuditService}), not entity
 * lifecycle callbacks — so this listener must carry no dependency on audit infrastructure.
 */
class TimestampingListenerTest {

    @Test
    void should_declare_no_fields_referencing_audit_infrastructure() {
        Field[] fields = TimestampingListener.class.getDeclaredFields();

        assertThat(fields).noneMatch(TimestampingListenerTest::referencesAuditInfrastructure);
    }

    @Test
    void should_declare_no_constructor_dependency_on_audit_infrastructure() {
        Constructor<?>[] constructors = TimestampingListener.class.getDeclaredConstructors();

        for (Constructor<?> constructor : constructors) {
            assertThat(constructor.getParameterTypes())
                .noneMatch(TimestampingListenerTest::isAuditType);
        }
    }

    private static boolean referencesAuditInfrastructure(Field field) {
        return isAuditType(field.getType());
    }

    private static boolean isAuditType(Class<?> type) {
        return type.getPackageName().startsWith("com.crm.foundation.Audit")
            || type.getSimpleName().contains("Audit");
    }
}
