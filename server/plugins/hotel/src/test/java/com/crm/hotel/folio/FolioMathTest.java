package com.crm.hotel.folio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolioMathTest {

    @Test
    void balance_is_room_plus_charges_minus_payments() {
        assertThat(FolioMath.balance(2_000_000, 350_000, 1_000_000)).isEqualTo(1_350_000);
        assertThat(FolioMath.balance(2_000_000, 0, 0)).isEqualTo(2_000_000);
    }

    @Test
    void zero_balance() {
        assertThat(FolioMath.balance(2_000_000, 150_000, 2_150_000)).isZero();
        assertThat(FolioMath.balance(0, 0, 0)).isZero();
    }

    @Test
    void overflow_throws() {
        assertThatThrownBy(() -> FolioMath.balance(Long.MAX_VALUE, 1, 0)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> FolioMath.balance(Long.MIN_VALUE, 0, 1)).isInstanceOf(ArithmeticException.class);
    }
}
