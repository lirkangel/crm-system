package com.crm.dental.previsit;

import com.crm.dental.DentalErrors;
import com.crm.foundation.Plugin.PluginProblem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrevisitRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private static PrevisitRequest.Identity identity() {
        return new PrevisitRequest.Identity("CCCD", "079090123456", "TRAN THI BINH", LocalDate.of(1990, 5, 17), "VN", "12 Le Loi");
    }

    private static PrevisitRequest request(PrevisitRequest.Identity identity, AllergyAnswer allergies, MedicineAnswer medicines) {
        return new PrevisitRequest(identity, null, null, "0912345678", allergies, medicines,
            new ReasonAnswer(ReasonCode.CHECKUP, null), true, null);
    }

    private static PrevisitRequest ok() {
        return request(identity(), new AllergyAnswer(true, null, null), new MedicineAnswer(true, null));
    }

    private static void assertInvalid(PrevisitRequest request) {
        assertThatThrownBy(() -> PrevisitRules.check(request, false, TODAY))
            .isInstanceOfSatisfying(PluginProblem.class, p -> {
                assertThat(p.status()).isEqualTo(400);
                assertThat(p.getMessage() + p.code()).contains(DentalErrors.INVALID_FORM)
                    .doesNotContain("079090123456").doesNotContain("0912345678");
            });
    }

    @Test
    void child_boundary_is_the_fourteenth_birthday() {
        LocalDate dob = LocalDate.of(2012, 6, 15);

        assertThat(PrevisitRules.isChild(dob, LocalDate.of(2026, 6, 14))).as("day before").isTrue();
        assertThat(PrevisitRules.isChild(dob, LocalDate.of(2026, 6, 15))).as("birthday").isFalse();
        assertThat(PrevisitRules.isChild(dob, LocalDate.of(2026, 6, 16))).as("day after").isFalse();
    }

    @Test
    void feb_29_turns_14_on_march_1_of_a_common_year() {
        LocalDate dob = LocalDate.of(2012, 2, 29);

        assertThat(PrevisitRules.isChild(dob, LocalDate.of(2026, 2, 27))).isTrue();
        assertThat(PrevisitRules.isChild(dob, LocalDate.of(2026, 2, 28))).as("not yet 14 on 28 Feb").isTrue();
        assertThat(PrevisitRules.isChild(dob, LocalDate.of(2026, 3, 1))).as("14 on 1 Mar").isFalse();
        assertThat(PrevisitRules.isChild(dob, LocalDate.of(2026, 3, 2))).isFalse();
    }

    @Test
    void valid_adult_form_passes() {
        assertThat(PrevisitRules.check(ok(), false, TODAY).phone()).isEqualTo("+84912345678");
    }

    @Test
    void items_containing_null_is_400() {
        Set<AllergyCode> items = new HashSet<>();
        items.add(null);
        items.add(AllergyCode.LATEX);

        assertInvalid(request(identity(), new AllergyAnswer(false, items, null), new MedicineAnswer(true, null)));
    }

    @Test
    void none_with_other_text_is_400() {
        assertInvalid(request(identity(), new AllergyAnswer(true, null, "Kiwi"), new MedicineAnswer(true, null)));
        assertInvalid(request(identity(), new AllergyAnswer(true, Set.of(AllergyCode.LATEX), null), new MedicineAnswer(true, null)));
        assertInvalid(request(identity(), new AllergyAnswer(true, null, null), new MedicineAnswer(true, "aspirin")));
    }

    @Test
    void control_characters_are_400() {
        assertInvalid(request(identity(), new AllergyAnswer(true, null, null), new MedicineAnswer(false, "aspirin\u0000")));
        assertInvalid(request(identity(), new AllergyAnswer(false, Set.of(AllergyCode.OTHER), "kiwi\u0007"),
            new MedicineAnswer(true, null)));
        PrevisitRequest.Identity bad = new PrevisitRequest.Identity("CCCD", "0790\u00001", "A", LocalDate.of(1990, 1, 1), "VN", "x");
        assertInvalid(request(bad, new AllergyAnswer(true, null, null), new MedicineAnswer(true, null)));
    }

    @Test
    void checked_never_prints_values() {
        assertThat(PrevisitRules.check(ok(), false, TODAY).toString()).doesNotContain("079090123456")
            .doesNotContain("+84912345678");
    }
}
