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
        assertInvalid(request, false);
    }

    private static void assertInvalid(PrevisitRequest request, boolean child) {
        assertThatThrownBy(() -> PrevisitRules.check(request, child, TODAY))
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
    void free_text_answers_allow_newline_return_and_tab() {
        PrevisitRules.Checked medicines = PrevisitRules.check(request(identity(), new AllergyAnswer(true, null, null),
            new MedicineAnswer(false, "aspirin\n500 mg\tdaily\r\nmetformin\n")), false, TODAY);
        assertThat(medicines.medicines().text()).isEqualTo("aspirin\n500 mg\tdaily\r\nmetformin");

        PrevisitRules.Checked allergies = PrevisitRules.check(request(identity(),
            new AllergyAnswer(false, Set.of(AllergyCode.OTHER), "kiwi\nand\tplum"), new MedicineAnswer(true, null)), false, TODAY);
        assertThat(allergies.allergies().otherText()).isEqualTo("kiwi\nand\tplum");

        PrevisitRequest reason = new PrevisitRequest(identity(), null, null, "0912345678",
            new AllergyAnswer(true, null, null), new MedicineAnswer(true, null),
            new ReasonAnswer(ReasonCode.OTHER, "line one\r\nline two\ttabbed"), true, null);
        assertThat(PrevisitRules.check(reason, false, TODAY).reason().text()).isEqualTo("line one\r\nline two\ttabbed");
    }

    @Test
    void free_text_still_rejects_every_other_control_character() {
        for (String bad : new String[] {"a\u0000b", "a\u0007b", "a\u001bb", "a\u000bb", "a\u000cb", "a\u007fb", "a\u0085b"}) {
            assertInvalid(request(identity(), new AllergyAnswer(true, null, null), new MedicineAnswer(false, bad)));
            assertInvalid(request(identity(), new AllergyAnswer(false, Set.of(AllergyCode.OTHER), bad), new MedicineAnswer(true, null)));
            assertInvalid(new PrevisitRequest(identity(), null, null, "0912345678", new AllergyAnswer(true, null, null),
                new MedicineAnswer(true, null), new ReasonAnswer(ReasonCode.PAIN, bad), true, null));
        }
    }

    @Test
    void identity_and_phone_fields_reject_newline_return_and_tab() {
        for (String bad : new String[] {"\n", "\r", "\t"}) {
            for (int field = 0; field < 3; field++) {
                String idNumber = field == 0 ? "0790" + bad + "1" : "079090123456";
                String fullName = field == 1 ? "TRAN" + bad + "BINH" : "TRAN THI BINH";
                String address = field == 2 ? "12 Le Loi" + bad + "Hue" : "12 Le Loi";
                PrevisitRequest.Identity id = new PrevisitRequest.Identity("CCCD", idNumber, fullName, LocalDate.of(1990, 5, 17), "VN", address);
                assertInvalid(request(id, new AllergyAnswer(true, null, null), new MedicineAnswer(true, null)));
            }
            assertInvalid(new PrevisitRequest(identity(), null, null, "09123" + bad + "45678", new AllergyAnswer(true, null, null),
                new MedicineAnswer(true, null), new ReasonAnswer(ReasonCode.CHECKUP, null), true, null));
            assertInvalid(new PrevisitRequest(null, "Mother" + bad + "Name", "0912345678", "0912345678",
                new AllergyAnswer(true, null, null), new MedicineAnswer(true, null), new ReasonAnswer(ReasonCode.CHECKUP, null), true, null), true);
            assertInvalid(new PrevisitRequest(null, "Mother", "09123" + bad + "45678", "0912345678",
                new AllergyAnswer(true, null, null), new MedicineAnswer(true, null), new ReasonAnswer(ReasonCode.CHECKUP, null), true, null), true);
        }
    }

    @Test
    void unicode_line_separators_become_newlines_in_free_text() {
        PrevisitRules.Checked checked = PrevisitRules.check(request(identity(), new AllergyAnswer(true, null, null),
            new MedicineAnswer(false, "aspirin\u2028500 mg\u2029daily")), false, TODAY);
        assertThat(checked.medicines().text()).isEqualTo("aspirin\n500 mg\ndaily");
        PrevisitRules.Checked other = PrevisitRules.check(request(identity(),
            new AllergyAnswer(false, Set.of(AllergyCode.OTHER), "kiwi\u2028plum"), new MedicineAnswer(true, null)), false, TODAY);
        assertThat(other.allergies().otherText()).isEqualTo("kiwi\nplum");
    }

    @Test
    void unicode_line_separators_rejected_in_identity_parent_and_phone() {
        for (String bad : new String[] {"\u2028", "\u2029"}) {
            PrevisitRequest.Identity id = new PrevisitRequest.Identity("CCCD", "079090123456", "TRAN" + bad + "BINH",
                LocalDate.of(1990, 5, 17), "VN", "12 Le Loi");
            assertInvalid(request(id, new AllergyAnswer(true, null, null), new MedicineAnswer(true, null)));
            PrevisitRequest.Identity addr = new PrevisitRequest.Identity("CCCD", "079090123456", "TRAN THI BINH",
                LocalDate.of(1990, 5, 17), "VN", "12 Le" + bad + "Loi");
            assertInvalid(request(addr, new AllergyAnswer(true, null, null), new MedicineAnswer(true, null)));
            assertInvalid(new PrevisitRequest(identity(), null, null, "09123" + bad + "45678", new AllergyAnswer(true, null, null),
                new MedicineAnswer(true, null), new ReasonAnswer(ReasonCode.CHECKUP, null), true, null));
            assertInvalid(new PrevisitRequest(null, "Mother" + bad + "Name", "0912345678", "0912345678",
                new AllergyAnswer(true, null, null), new MedicineAnswer(true, null), new ReasonAnswer(ReasonCode.CHECKUP, null), true, null), true);
        }
    }

    @Test
    void checked_never_prints_values() {
        assertThat(PrevisitRules.check(ok(), false, TODAY).toString()).doesNotContain("079090123456")
            .doesNotContain("+84912345678");
    }
}
