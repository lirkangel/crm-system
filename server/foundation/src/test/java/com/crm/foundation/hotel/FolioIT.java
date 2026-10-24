package com.crm.foundation.hotel;

import com.crm.foundation.Component.JwtTokenProvider;
import com.crm.foundation.Domain.User;
import com.crm.foundation.Repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The folio over HTTP: room charge, extra charges, payments (incl. OTA prepayment), voids, balance, cancel rule. */
class FolioIT extends HotelITSupport {

    private static final String RESERVATIONS = HOTEL_API + "/reservations";
    /** Two nights at 1,000,000. */
    private static final long ROOM_CHARGE = 2_000_000L;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider tokens;

    @Test
    void room_charge_is_booking_total_and_balance_starts_there() {
        String token = adminToken();
        UUID id = confirmedBooking(token);

        ResponseEntity<String> response = get(folio(id), token);

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode folio = data(response);
        assertThat(folio.path("reservationId").asText()).isEqualTo(id.toString());
        assertThat(folio.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(folio.path("roomChargeGrossVnd").asLong()).isEqualTo(ROOM_CHARGE);
        assertThat(folio.path("charges")).isEmpty();
        assertThat(folio.path("payments")).isEmpty();
        assertThat(folio.path("chargesTotalGrossVnd").asLong()).isZero();
        assertThat(folio.path("paymentsTotalGrossVnd").asLong()).isZero();
        assertThat(folio.path("balanceGrossVnd").asLong()).isEqualTo(ROOM_CHARGE);
        ResponseEntity<String> unknown = get(folio(UUID.randomUUID()), token);
        assertThat(unknown.getStatusCode().value()).as(unknown.getBody()).isEqualTo(404);
        assertThat(json(unknown).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
    }

    @Test
    void ota_prepayment_on_confirmed_booking() {
        String token = adminToken();
        UUID id = confirmedBooking(token);

        ResponseEntity<String> paid = pay(token, id, "OTA", "BOOKING_COM", "BK-4711", 500_000);

        assertThat(paid.getStatusCode().value()).as(paid.getBody()).isEqualTo(201);
        JsonNode folio = data(paid);
        assertThat(folio.path("paymentsTotalGrossVnd").asLong()).isEqualTo(500_000);
        assertThat(folio.path("balanceGrossVnd").asLong()).isEqualTo(ROOM_CHARGE - 500_000);
        JsonNode payment = folio.path("payments").get(0);
        assertThat(payment.path("id").asText()).isNotBlank();
        assertThat(payment.path("method").asText()).isEqualTo("OTA");
        assertThat(payment.path("otaChannel").asText()).isEqualTo("BOOKING_COM");
        assertThat(payment.path("reference").asText()).isEqualTo("BK-4711");
        assertThat(payment.path("amountGrossVnd").asLong()).isEqualTo(500_000);
        assertThat(payment.path("createdAt").isNull()).isFalse();
        assertThat(payment.path("voidedAt").isNull()).isTrue();
        assertThat(payment.path("version").asLong()).isZero();
        assertThat(data(get(folio(id), token)).path("balanceGrossVnd").asLong()).isEqualTo(ROOM_CHARGE - 500_000);
    }

    @Test
    void charge_only_while_checked_in() {
        String token = adminToken();
        UUID id = confirmedBooking(token);

        ResponseEntity<String> beforeCheckIn = charge(token, id, "Minibar", 100_000);
        checkIn(token, id);
        ResponseEntity<String> whileCheckedIn = charge(token, id, "Minibar", 100_000);
        ResponseEntity<String> unknown = charge(token, UUID.randomUUID(), "Minibar", 100_000);
        jdbc.update("UPDATE hotel.reservation SET status = 'CHECKED_OUT' WHERE id = ?", id);
        ResponseEntity<String> afterCheckOut = charge(token, id, "Minibar", 100_000);
        ResponseEntity<String> paymentAfterCheckOut = pay(token, id, "CASH", null, null, 1_000);
        UUID cancelled = confirmedBooking(token);
        assertThat(post(RESERVATIONS + "/" + cancelled + "/cancel", token, null).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> paymentOnCancelled = pay(token, cancelled, "CASH", null, null, 1_000);

        assertThat(beforeCheckIn.getStatusCode().value()).as(beforeCheckIn.getBody()).isEqualTo(409);
        assertThat(json(beforeCheckIn).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(whileCheckedIn.getStatusCode().value()).as(whileCheckedIn.getBody()).isEqualTo(201);
        assertThat(data(whileCheckedIn).path("charges")).hasSize(1);
        assertThat(unknown.getStatusCode().value()).as(unknown.getBody()).isEqualTo(404);
        assertThat(json(unknown).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
        assertThat(afterCheckOut.getStatusCode().value()).as(afterCheckOut.getBody()).isEqualTo(409);
        assertThat(json(afterCheckOut).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(paymentAfterCheckOut.getStatusCode().value()).as(paymentAfterCheckOut.getBody()).isEqualTo(409);
        assertThat(json(paymentAfterCheckOut).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(paymentOnCancelled.getStatusCode().value()).as(paymentOnCancelled.getBody()).isEqualTo(409);
        assertThat(json(paymentOnCancelled).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        // the folio stays readable after check-out
        assertThat(get(folio(id), token).getStatusCode().value()).isEqualTo(200);
        assertThat(lineCount("folio_charge", id)).isEqualTo(1);
        assertThat(lineCount("folio_payment", id)).isZero();
    }

    @Test
    void minibar_charge_then_cash_payment_zeroes_balance() {
        String token = adminToken();
        UUID id = checkedInStay(token);

        ResponseEntity<String> charged = charge(token, id, "  Minibar  ", 150_000);
        ResponseEntity<String> paid = pay(token, id, "CASH", null, null, ROOM_CHARGE + 150_000);

        assertThat(charged.getStatusCode().value()).as(charged.getBody()).isEqualTo(201);
        JsonNode afterCharge = data(charged);
        assertThat(afterCharge.path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(afterCharge.path("chargesTotalGrossVnd").asLong()).isEqualTo(150_000);
        assertThat(afterCharge.path("balanceGrossVnd").asLong()).isEqualTo(ROOM_CHARGE + 150_000);
        JsonNode line = afterCharge.path("charges").get(0);
        assertThat(line.path("description").asText()).isEqualTo("Minibar");
        assertThat(line.path("amountGrossVnd").asLong()).isEqualTo(150_000);
        assertThat(line.path("voidedAt").isNull()).isTrue();
        assertThat(line.path("voidReason").isNull()).isTrue();
        assertThat(line.path("version").asLong()).isZero();
        assertThat(paid.getStatusCode().value()).as(paid.getBody()).isEqualTo(201);
        assertThat(data(paid).path("balanceGrossVnd").asLong()).isZero();
        assertThat(data(paid).path("payments").get(0).path("method").asText()).isEqualTo("CASH");
        assertThat(data(paid).path("payments").get(0).path("otaChannel").isNull()).isTrue();
    }

    @Test
    void overpayment_is_409() {
        String token = adminToken();
        UUID id = checkedInStay(token);

        ResponseEntity<String> tooMuch = pay(token, id, "CARD", null, "slip 1", ROOM_CHARGE + 1);
        ResponseEntity<String> exact = pay(token, id, "CARD", null, "slip 2", ROOM_CHARGE);
        ResponseEntity<String> nothingLeft = pay(token, id, "CASH", null, null, 1);

        assertThat(tooMuch.getStatusCode().value()).as(tooMuch.getBody()).isEqualTo(409);
        assertThat(json(tooMuch).path("code").asText()).isEqualTo("HOTEL_OVERPAYMENT");
        assertThat(exact.getStatusCode().value()).as(exact.getBody()).isEqualTo(201);
        assertThat(nothingLeft.getStatusCode().value()).as(nothingLeft.getBody()).isEqualTo(409);
        assertThat(json(nothingLeft).path("code").asText()).isEqualTo("HOTEL_OVERPAYMENT");
        assertThat(lineCount("folio_payment", id)).isEqualTo(1);
    }

    @Test
    void void_charge_that_would_make_balance_negative_is_409() {
        String token = adminToken();
        UUID id = checkedInStay(token);
        UUID chargeId = lineId(charge(token, id, "Laundry", 300_000), "charges");
        UUID paymentId = lineId(pay(token, id, "CASH", null, null, ROOM_CHARGE + 300_000), "payments");

        ResponseEntity<String> refused = voidLine(token, id, "charges", chargeId, "entered twice");

        assertThat(refused.getStatusCode().value()).as(refused.getBody()).isEqualTo(409);
        assertThat(json(refused).path("code").asText()).isEqualTo("HOTEL_OVERPAYMENT");
        assertThat(data(get(folio(id), token)).path("chargesTotalGrossVnd").asLong()).isEqualTo(300_000);

        ResponseEntity<String> paymentVoided = voidLine(token, id, "payments", paymentId, "wrong slip");
        assertThat(paymentVoided.getStatusCode().value()).as(paymentVoided.getBody()).isEqualTo(200);
        assertThat(data(paymentVoided).path("balanceGrossVnd").asLong()).isEqualTo(ROOM_CHARGE + 300_000);
        ResponseEntity<String> chargeVoided = voidLine(token, id, "charges", chargeId, "entered twice");
        assertThat(chargeVoided.getStatusCode().value()).as(chargeVoided.getBody()).isEqualTo(200);
        assertThat(data(chargeVoided).path("balanceGrossVnd").asLong()).isEqualTo(ROOM_CHARGE);
    }

    @Test
    void voided_lines_listed_but_not_totalled() {
        String token = adminToken();
        UUID id = checkedInStay(token);
        UUID typo = lineId(charge(token, id, "Minibar typo", 100_000), "charges");
        charge(token, id, "Minibar", 50_000);
        UUID wrong = lineId(pay(token, id, "CASH", null, null, 100_000), "payments");

        ResponseEntity<String> voidedCharge = voidLine(token, id, "charges", typo, "typo");
        ResponseEntity<String> voidedPayment = voidLine(token, id, "payments", wrong, "wrong amount");

        assertThat(voidedCharge.getStatusCode().value()).as(voidedCharge.getBody()).isEqualTo(200);
        assertThat(voidedPayment.getStatusCode().value()).as(voidedPayment.getBody()).isEqualTo(200);
        JsonNode folio = data(get(folio(id), token));
        assertThat(folio.path("charges")).hasSize(2);
        assertThat(folio.path("payments")).hasSize(1);
        assertThat(folio.path("chargesTotalGrossVnd").asLong()).isEqualTo(50_000);
        assertThat(folio.path("paymentsTotalGrossVnd").asLong()).isZero();
        assertThat(folio.path("balanceGrossVnd").asLong()).isEqualTo(ROOM_CHARGE + 50_000);
        JsonNode voided = byId(folio.path("charges"), typo);
        assertThat(voided.path("voidedAt").isNull()).isFalse();
        assertThat(voided.path("voidReason").asText()).isEqualTo("typo");
        assertThat(voided.path("version").asLong()).isEqualTo(1L);
        assertThat(voided.path("amountGrossVnd").asLong()).isEqualTo(100_000);
        JsonNode voidedPay = folio.path("payments").get(0);
        assertThat(voidedPay.path("voidedAt").isNull()).isFalse();
        assertThat(voidedPay.path("voidReason").asText()).isEqualTo("wrong amount");
        long active = 0;
        for (JsonNode line : folio.path("charges")) {
            if (line.path("voidedAt").isNull()) {
                active += line.path("amountGrossVnd").asLong();
            }
        }
        assertThat(active).isEqualTo(50_000);
    }

    @Test
    void void_twice_is_409() {
        String token = adminToken();
        UUID id = checkedInStay(token);
        UUID chargeId = lineId(charge(token, id, "Minibar", 100_000), "charges");
        UUID paymentId = lineId(pay(token, id, "CASH", null, null, 100_000), "payments");
        assertThat(voidLine(token, id, "charges", chargeId, "mistake").getStatusCode().value()).isEqualTo(200);
        assertThat(voidLine(token, id, "payments", paymentId, "mistake").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> chargeAgain = voidLine(token, id, "charges", chargeId, "again");
        ResponseEntity<String> paymentAgain = voidLine(token, id, "payments", paymentId, "again");

        assertThat(chargeAgain.getStatusCode().value()).as(chargeAgain.getBody()).isEqualTo(409);
        assertThat(json(chargeAgain).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(paymentAgain.getStatusCode().value()).as(paymentAgain.getBody()).isEqualTo(409);
        assertThat(json(paymentAgain).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        // the first void's reason is kept
        assertThat(jdbc.queryForObject(
            "SELECT void_reason FROM hotel.folio_charge WHERE id = ?", String.class, chargeId)).isEqualTo("mistake");
    }

    @Test
    void void_unknown_or_foreign_line_is_404_and_needs_an_open_stay() {
        String token = adminToken();
        UUID id = checkedInStay(token);
        UUID other = checkedInStay(token);
        UUID foreignCharge = lineId(charge(token, other, "Minibar", 10_000), "charges");
        UUID foreignPayment = lineId(pay(token, other, "CASH", null, null, 10_000), "payments");
        UUID ownCharge = lineId(charge(token, id, "Minibar", 10_000), "charges");
        UUID ownPayment = lineId(pay(token, id, "CASH", null, null, 10_000), "payments");

        ResponseEntity<String> unknownCharge = voidLine(token, id, "charges", UUID.randomUUID(), "x");
        ResponseEntity<String> foreignChargeResponse = voidLine(token, id, "charges", foreignCharge, "x");
        ResponseEntity<String> foreignPaymentResponse = voidLine(token, id, "payments", foreignPayment, "x");
        ResponseEntity<String> unknownReservation = voidLine(token, UUID.randomUUID(), "payments", ownPayment, "x");
        jdbc.update("UPDATE hotel.reservation SET status = 'CHECKED_OUT' WHERE id = ?", id);
        ResponseEntity<String> chargeAfterCheckOut = voidLine(token, id, "charges", ownCharge, "late");
        ResponseEntity<String> paymentAfterCheckOut = voidLine(token, id, "payments", ownPayment, "late");

        for (ResponseEntity<String> notFound :
                List.of(unknownCharge, foreignChargeResponse, foreignPaymentResponse, unknownReservation)) {
            assertThat(notFound.getStatusCode().value()).as(notFound.getBody()).isEqualTo(404);
            assertThat(json(notFound).path("code").asText()).isEqualTo("HOTEL_NOT_FOUND");
        }
        assertThat(chargeAfterCheckOut.getStatusCode().value()).as(chargeAfterCheckOut.getBody()).isEqualTo(409);
        assertThat(json(chargeAfterCheckOut).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(paymentAfterCheckOut.getStatusCode().value()).as(paymentAfterCheckOut.getBody()).isEqualTo(409);
        assertThat(json(paymentAfterCheckOut).path("code").asText()).isEqualTo("HOTEL_INVALID_STATE");
        assertThat(jdbc.queryForObject("SELECT voided_at IS NULL FROM hotel.folio_charge WHERE id = ?",
            Boolean.class, foreignCharge)).isTrue();
    }

    @Test
    void ota_payment_without_channel_is_400() {
        String token = adminToken();
        UUID id = confirmedBooking(token);

        ResponseEntity<String> otaWithoutChannel = pay(token, id, "OTA", null, "BK-1", 100_000);
        ResponseEntity<String> channelOnCash = pay(token, id, "CASH", "AGODA", null, 100_000);
        ResponseEntity<String> zeroAmount = pay(token, id, "CASH", null, null, 0);
        ResponseEntity<String> noMethod = pay(token, id, null, null, null, 1_000);
        ResponseEntity<String> blankDescription = charge(token, id, " ", 1_000);
        ResponseEntity<String> negativeCharge = charge(token, id, "Minibar", -5);

        assertThat(otaWithoutChannel.getStatusCode().value()).as(otaWithoutChannel.getBody()).isEqualTo(400);
        assertThat(json(otaWithoutChannel).path("code").asText()).isEqualTo("HOTEL_INVALID_PAYMENT");
        assertThat(channelOnCash.getStatusCode().value()).as(channelOnCash.getBody()).isEqualTo(400);
        assertThat(json(channelOnCash).path("code").asText()).isEqualTo("HOTEL_INVALID_PAYMENT");
        assertThat(zeroAmount.getStatusCode().value()).as(zeroAmount.getBody()).isEqualTo(400);
        assertThat(noMethod.getStatusCode().value()).as(noMethod.getBody()).isEqualTo(400);
        assertThat(blankDescription.getStatusCode().value()).as(blankDescription.getBody()).isEqualTo(400);
        assertThat(negativeCharge.getStatusCode().value()).as(negativeCharge.getBody()).isEqualTo(400);
        assertThat(lineCount("folio_payment", id)).isZero();
    }

    @Test
    void charge_over_the_cap_is_400_validation_error() {
        String token = adminToken();
        UUID id = confirmedBooking(token);

        ResponseEntity<String> overCap = charge(token, id, "Absurd", 1_000_000_000_001L);
        ResponseEntity<String> huge = charge(token, id, "Overflow", Long.MAX_VALUE);

        assertThat(overCap.getStatusCode().value()).as(overCap.getBody()).isEqualTo(400);
        assertThat(json(overCap).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(huge.getStatusCode().value()).as(huge.getBody()).isEqualTo(400);
        assertThat(json(huge).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(lineCount("folio_charge", id)).isZero();
    }

    @Test
    void payment_over_the_cap_is_400_validation_error() {
        String token = adminToken();
        UUID id = confirmedBooking(token);

        ResponseEntity<String> overCap = pay(token, id, "CASH", null, null, 1_000_000_000_001L);
        ResponseEntity<String> huge = pay(token, id, "CASH", null, null, Long.MAX_VALUE);

        assertThat(overCap.getStatusCode().value()).as(overCap.getBody()).isEqualTo(400);
        assertThat(json(overCap).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(huge.getStatusCode().value()).as(huge.getBody()).isEqualTo(400);
        assertThat(json(huge).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(lineCount("folio_payment", id)).isZero();
    }

    @Test
    void void_needs_a_reason() {
        String token = adminToken();
        UUID id = checkedInStay(token);
        UUID chargeId = lineId(charge(token, id, "Minibar", 10_000), "charges");

        ResponseEntity<String> blank = voidLine(token, id, "charges", chargeId, " ");
        ResponseEntity<String> missing = post(folio(id) + "/charges/" + chargeId + "/void", token, Map.of());

        assertThat(blank.getStatusCode().value()).as(blank.getBody()).isEqualTo(400);
        assertThat(missing.getStatusCode().value()).as(missing.getBody()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT voided_at IS NULL FROM hotel.folio_charge WHERE id = ?",
            Boolean.class, chargeId)).isTrue();
    }

    @Test
    void cancel_with_payment_refused_until_voided() {
        String token = adminToken();
        UUID id = confirmedBooking(token);
        UUID paymentId = lineId(pay(token, id, "OTA", "AGODA", "AG-1", 700_000), "payments");

        ResponseEntity<String> refused = post(RESERVATIONS + "/" + id + "/cancel", token, null);
        assertThat(refused.getStatusCode().value()).as(refused.getBody()).isEqualTo(409);
        assertThat(json(refused).path("code").asText()).isEqualTo("HOTEL_PAYMENTS_ON_FOLIO");
        assertThat(status(id)).isEqualTo("CONFIRMED");

        assertThat(voidLine(token, id, "payments", paymentId, "refunded outside").getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> cancelled = post(RESERVATIONS + "/" + id + "/cancel", token, null);

        assertThat(cancelled.getStatusCode().value()).as(cancelled.getBody()).isEqualTo(200);
        assertThat(data(cancelled).path("status").asText()).isEqualTo("CANCELLED");
        // read-only afterwards, but still readable with the voided payment listed
        JsonNode folio = data(get(folio(id), token));
        assertThat(folio.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(folio.path("payments")).hasSize(1);
        assertThat(folio.path("paymentsTotalGrossVnd").asLong()).isZero();
        ResponseEntity<String> late = pay(token, id, "CASH", null, null, 1_000);
        assertThat(late.getStatusCode().value()).as(late.getBody()).isEqualTo(409);
    }

    @Test
    void folio_permissions() {
        String admin = adminToken();
        UUID id = checkedInStay(admin);
        UUID chargeId = lineId(charge(admin, id, "Minibar", 10_000), "charges");
        UUID paymentId = lineId(pay(admin, id, "CASH", null, null, 10_000), "payments");
        String none = tokenWith("hotel_it_folio_none", "hotel.reservations.read");
        String reader = tokenWith("hotel_it_folio_reader", "hotel.folio.read");
        String charger = tokenWith("hotel_it_folio_charger", "hotel.folio.charge");
        String payer = tokenWith("hotel_it_folio_payer", "hotel.folio.pay");

        assertThat(get(folio(id), none).getStatusCode().value()).isEqualTo(403);
        assertThat(get(folio(id), charger).getStatusCode().value()).isEqualTo(403);
        assertThat(get(folio(id), reader).getStatusCode().value()).isEqualTo(200);
        assertThat(charge(reader, id, "Minibar", 1_000).getStatusCode().value()).isEqualTo(403);
        assertThat(charge(payer, id, "Minibar", 1_000).getStatusCode().value()).isEqualTo(403);
        assertThat(charge(none, id, "Minibar", 1_000).getStatusCode().value()).isEqualTo(403);
        assertThat(voidLine(reader, id, "charges", chargeId, "x").getStatusCode().value()).isEqualTo(403);
        assertThat(voidLine(payer, id, "charges", chargeId, "x").getStatusCode().value()).isEqualTo(403);
        assertThat(pay(reader, id, "CASH", null, null, 1_000).getStatusCode().value()).isEqualTo(403);
        assertThat(pay(charger, id, "CASH", null, null, 1_000).getStatusCode().value()).isEqualTo(403);
        assertThat(pay(none, id, "CASH", null, null, 1_000).getStatusCode().value()).isEqualTo(403);
        assertThat(voidLine(reader, id, "payments", paymentId, "x").getStatusCode().value()).isEqualTo(403);
        assertThat(voidLine(charger, id, "payments", paymentId, "x").getStatusCode().value()).isEqualTo(403);
        assertThat(get(folio(id), null).getStatusCode().value()).isEqualTo(401);

        assertThat(charge(charger, id, "Laundry", 1_000).getStatusCode().value()).isEqualTo(201);
        assertThat(voidLine(charger, id, "charges", chargeId, "mistake").getStatusCode().value()).isEqualTo(200);
        assertThat(pay(payer, id, "CASH", null, null, 1_000).getStatusCode().value()).isEqualTo(201);
        assertThat(voidLine(payer, id, "payments", paymentId, "mistake").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void audit_and_change_events_for_lines() {
        String token = adminToken();
        UUID id = checkedInStay(token);
        UUID chargeId = lineId(charge(token, id, "Minibar", 120_000), "charges");
        UUID paymentId = lineId(pay(token, id, "BANK_TRANSFER", null, "FT-77", 500_000), "payments");
        assertThat(voidLine(token, id, "charges", chargeId, "typo").getStatusCode().value()).isEqualTo(200);
        assertThat(voidLine(token, id, "payments", paymentId, "wrong account").getStatusCode().value()).isEqualTo(200);

        assertThat(changes(chargeId)).containsExactly("FolioCharge:CREATE:0", "FolioCharge:UPDATE:1");
        assertThat(changes(paymentId)).containsExactly("FolioPayment:CREATE:0", "FolioPayment:UPDATE:1");

        JsonNode chargeAdd = audit(chargeId, "FOLIO_CHARGE_ADD", "FolioCharge");
        assertThat(chargeAdd.path("before").isNull()).isTrue();
        assertThat(chargeAdd.path("after").path("id").asText()).isEqualTo(chargeId.toString());
        assertThat(chargeAdd.path("after").path("description").asText()).isEqualTo("Minibar");
        assertThat(chargeAdd.path("after").path("amountGrossVnd").asLong()).isEqualTo(120_000);
        assertThat(chargeAdd.path("after").path("voidedAt").isNull()).isTrue();
        JsonNode chargeVoid = audit(chargeId, "FOLIO_CHARGE_VOID", "FolioCharge");
        assertThat(chargeVoid.path("before").path("voidedAt").isNull()).isTrue();
        assertThat(chargeVoid.path("after").path("voidedAt").isNull()).isFalse();
        assertThat(chargeVoid.path("after").path("voidReason").asText()).isEqualTo("typo");
        JsonNode paymentAdd = audit(paymentId, "FOLIO_PAYMENT_ADD", "FolioPayment");
        assertThat(paymentAdd.path("before").isNull()).isTrue();
        assertThat(paymentAdd.path("after").path("method").asText()).isEqualTo("BANK_TRANSFER");
        assertThat(paymentAdd.path("after").path("reference").asText()).isEqualTo("FT-77");
        assertThat(paymentAdd.path("after").path("amountGrossVnd").asLong()).isEqualTo(500_000);
        JsonNode paymentVoid = audit(paymentId, "FOLIO_PAYMENT_VOID", "FolioPayment");
        assertThat(paymentVoid.path("before").path("voidedAt").isNull()).isTrue();
        assertThat(paymentVoid.path("after").path("voidReason").asText()).isEqualTo("wrong account");
        assertThat(jdbc.queryForObject(
            "SELECT user_id IS NOT NULL FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND entity_id = ? "
                + "AND op = 'FOLIO_CHARGE_ADD'", Boolean.class, chargeId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT created_by IS NOT NULL FROM hotel.folio_charge WHERE id = ?",
            Boolean.class, chargeId)).isTrue();
    }

    @Test
    @Timeout(60)
    void concurrent_payments_cannot_overpay() throws Exception {
        String token = adminToken();
        UUID id = confirmedBooking(token);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Integer>> attempts = List.of(1, 2).stream()
                .map(n -> CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return pay(token, id, "CARD", null, "slip " + n, ROOM_CHARGE).getStatusCode().value();
                }, pool).orTimeout(30, TimeUnit.SECONDS))
                .toList();
            List<Integer> statuses = new ArrayList<>();
            for (CompletableFuture<Integer> attempt : attempts) {
                statuses.add(attempt.get(40, TimeUnit.SECONDS));
            }
            statuses.sort(null);

            assertThat(statuses).containsExactly(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(lineCount("folio_payment", id)).isEqualTo(1);
        assertThat(data(get(folio(id), token)).path("balanceGrossVnd").asLong()).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private static String folio(UUID id) {
        return RESERVATIONS + "/" + id + "/folio";
    }

    /** A confirmed booking for today..today+2 (two nights at 1,000,000). */
    private UUID confirmedBooking(String token) {
        String tag = tag();
        UUID room = newRoom(token, "FO" + tag, newRoomType(token, "FO" + tag, 2, 1_000_000, true), true);
        LocalDate today = hotelToday();
        return book(token, room, today, today.plusDays(2), 1);
    }

    private UUID checkedInStay(String token) {
        UUID id = confirmedBooking(token);
        checkIn(token, id);
        return id;
    }

    private void checkIn(String token, UUID id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("adults", List.of(adult("CCCD", "0790" + (10_000_000 + (long) (Math.random() * 89_000_000)), "Folio Guest")));
        body.put("roomId", null);
        ResponseEntity<String> response = post(RESERVATIONS + "/" + id + "/check-in", token, body);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
    }

    private ResponseEntity<String> charge(String token, UUID id, String description, long amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", description);
        body.put("amountGrossVnd", amount);
        return post(folio(id) + "/charges", token, body);
    }

    private ResponseEntity<String> pay(
            String token, UUID id, String method, String otaChannel, String reference, long amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", method);
        body.put("otaChannel", otaChannel);
        body.put("reference", reference);
        body.put("amountGrossVnd", amount);
        return post(folio(id) + "/payments", token, body);
    }

    private ResponseEntity<String> voidLine(String token, UUID id, String kind, UUID lineId, String reason) {
        return post(folio(id) + "/" + kind + "/" + lineId + "/void", token, Map.of("reason", reason));
    }

    /** Id of the last line of {@code kind} ({@code charges} / {@code payments}) in a folio response. */
    private UUID lineId(ResponseEntity<String> folioResponse, String kind) {
        assertThat(folioResponse.getStatusCode().value()).as(folioResponse.getBody()).isEqualTo(201);
        JsonNode lines = data(folioResponse).path(kind);
        return UUID.fromString(lines.get(lines.size() - 1).path("id").asText());
    }

    private static JsonNode byId(JsonNode lines, UUID id) {
        for (JsonNode line : lines) {
            if (id.toString().equals(line.path("id").asText())) {
                return line;
            }
        }
        throw new AssertionError("no line " + id + " in " + lines);
    }

    private String status(UUID reservationId) {
        return jdbc.queryForObject("SELECT status FROM hotel.reservation WHERE id = ?", String.class, reservationId);
    }

    private int lineCount(String table, UUID reservationId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM hotel." + table + " WHERE reservation_id = ?", Integer.class, reservationId);
    }

    private List<String> changes(UUID entityId) {
        return jdbc.query(
            "SELECT entity_type, op, version FROM change_event WHERE plugin_id = ? AND entity_id = ? ORDER BY version",
            (rs, row) -> rs.getString("entity_type") + ":" + rs.getString("op") + ":" + rs.getLong("version"),
            HOTEL_PLUGIN_ID, entityId);
    }

    /** {@code {before, after}} of the single audit row of {@code op} for the entity. */
    private JsonNode audit(UUID entityId, String op, String entityType) {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT entity_type, before_json::text AS before, after_json::text AS after
            FROM audit_event WHERE plugin_id = 'com.crm.hotel' AND entity_id = ? AND op = ?
            """, entityId, op);
        assertThat(row.get("entity_type")).isEqualTo(entityType);
        try {
            var node = objectMapper.createObjectNode();
            node.set("before", row.get("before") == null ? objectMapper.nullNode() : objectMapper.readTree((String) row.get("before")));
            node.set("after", row.get("after") == null ? objectMapper.nullNode() : objectMapper.readTree((String) row.get("after")));
            return node;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Access token of a persisted non-admin user holding only {@code permissions}. */
    private String tokenWith(String username, String... permissions) {
        User user = userRepository.findByUsername(username).orElseGet(() -> {
            User created = new User();
            created.setUsername(username);
            created.setEmail(username + "@example.com");
            created.setPassword(BCrypt.hashpw("irrelevant password", BCrypt.gensalt()));
            return userRepository.save(created);
        });
        return tokens.issueAccessToken(user, Set.of(permissions)).token();
    }

    /** Six hex characters: short enough for the 16-character room number column. */
    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
