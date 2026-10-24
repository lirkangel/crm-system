package com.crm.hotel.booking;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class OverlapGuardTest {

    @Test
    void no_overlap_exclusion_violation_is_recognized() {
        SQLException sql = new SQLException(
            "ERROR: conflicting key value violates exclusion constraint \"reservation_no_overlap\"", "23P01");

        assertThat(OverlapGuard.isNoOverlapViolation(new DataIntegrityViolationException("insert", sql))).isTrue();
    }

    @Test
    void other_violations_are_not() {
        SQLException otherExclusion = new SQLException(
            "ERROR: conflicting key value violates exclusion constraint \"something_else\"", "23P01");
        SQLException unique = new SQLException(
            "ERROR: duplicate key value violates unique constraint \"reservation_code_key\"", "23505");
        SQLException namedButNotExclusion = new SQLException("check \"reservation_no_overlap\"", "23514");

        assertThat(OverlapGuard.isNoOverlapViolation(new DataIntegrityViolationException("x", otherExclusion))).isFalse();
        assertThat(OverlapGuard.isNoOverlapViolation(new DataIntegrityViolationException("x", unique))).isFalse();
        assertThat(OverlapGuard.isNoOverlapViolation(new DataIntegrityViolationException("x", namedButNotExclusion))).isFalse();
        assertThat(OverlapGuard.isNoOverlapViolation(new DataIntegrityViolationException("no cause"))).isFalse();
    }
}
