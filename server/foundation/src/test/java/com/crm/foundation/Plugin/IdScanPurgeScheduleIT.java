package com.crm.foundation.Plugin;

import com.crm.foundation.hotel.HotelITSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hotel plugin registers its CCCD photo purge with the scheduler: one cron task, hourly on the hour in
 * Ho Chi Minh time, pointing at {@code IdScanPurgeJob.run}. Lives in this package to reach the child context.
 */
class IdScanPurgeScheduleIT extends HotelITSupport {

    @Autowired
    private PluginHost host;

    @Test
    void purge_job_is_registered_hourly_in_ho_chi_minh_time() throws NoSuchMethodException {
        ConfigurableApplicationContext hotel = PluginContexts.running(host, HOTEL_PLUGIN_ID);

        List<CronTask> cronTasks = hotel.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()
            .stream()
            .map(scheduled -> scheduled.getTask())
            .filter(CronTask.class::isInstance)
            .map(CronTask.class::cast)
            .toList();

        assertThat(cronTasks).hasSize(1);
        CronTask purge = cronTasks.get(0);
        assertThat(purge.getExpression()).isEqualTo("0 0 * * * *");
        // hotel classes are not on this classpath: compare by name
        ScheduledMethodRunnable runnable = methodRunnable(purge.getRunnable());
        assertThat(ClassUtils.getUserClass(runnable.getTarget()).getName())
            .isEqualTo("com.crm.hotel.scan.IdScanPurgeJob");
        assertThat(runnable.getMethod().getName()).isEqualTo("run");
        assertThat(runnable.getMethod().getAnnotation(Scheduled.class).zone()).isEqualTo("Asia/Ho_Chi_Minh");
    }

    /** Spring wraps the method runnable in an outcome-tracking decorator: peel Runnable fields until it shows. */
    private static ScheduledMethodRunnable methodRunnable(Runnable runnable) {
        Runnable current = runnable;
        while (!(current instanceof ScheduledMethodRunnable)) {
            Runnable inner = null;
            for (Field field : current.getClass().getDeclaredFields()) {
                if (Runnable.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        inner = (Runnable) field.get(current);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            assertThat(inner).as("a scheduled method runnable inside " + current.getClass()).isNotNull();
            current = inner;
        }
        return (ScheduledMethodRunnable) current;
    }
}
