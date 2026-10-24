package com.crm.hotel.folio;

import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.ChangeOp;
import com.crm.foundation.Plugin.ChangeRecorder;
import com.crm.foundation.Plugin.CurrentUser;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.domain.ReservationStatus;
import com.crm.hotel.repo.FolioChargeRepository;
import com.crm.hotel.repo.FolioPaymentRepository;
import com.crm.hotel.repo.ReservationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * A stay's folio: the room charge (the booking total), extra charges, payments and voids. Every write locks the
 * reservation row first and only then reads the balance or touches a line, so concurrent writes of one folio are
 * serialized (no overpayment race) and undo-check-in's "no charges" check can't interleave with a charge being added.
 */
@Service
@RequiredArgsConstructor
public class FolioService {

    static final String CHARGE = "FolioCharge";
    static final String PAYMENT = "FolioPayment";
    static final String AUDIT_CHARGE_ADD = "FOLIO_CHARGE_ADD";
    static final String AUDIT_CHARGE_VOID = "FOLIO_CHARGE_VOID";
    static final String AUDIT_PAYMENT_ADD = "FOLIO_PAYMENT_ADD";
    static final String AUDIT_PAYMENT_VOID = "FOLIO_PAYMENT_VOID";

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock hotelClock;
    private final ReservationRepository reservations;
    private final FolioChargeRepository charges;
    private final FolioPaymentRepository payments;
    private final ChangeRecorder changes;
    private final AuditRecorder audit;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    /**
     * The balance due of a folio (room charge + active charges - active payments). Doesn't lock: a caller that acts
     * on the result (check-out) holds the reservation lock itself.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404)
     */
    @Transactional(readOnly = true)
    public long balance(UUID reservationId) {
        return balance(reservations.findById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found")));
    }

    /** The folio of a reservation of any status (read-only once cancelled or checked out). */
    @Transactional(readOnly = true)
    public FolioView folio(UUID reservationId) {
        return view(reservations.findById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found")));
    }

    /**
     * Adds an extra charge.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404); {@code HOTEL_INVALID_STATE}
     *         (409) unless {@code CHECKED_IN}
     */
    @Transactional
    public FolioView addCharge(UUID reservationId, FolioChargeRequest request) {
        Reservation reservation = lock(reservationId);
        requireStatus(reservation, "Charges can only be added while the stay is checked in", ReservationStatus.CHECKED_IN);

        FolioCharge charge = new FolioCharge();
        charge.setReservationId(reservationId);
        charge.setDescription(request.description().trim());
        charge.setAmountGrossVnd(request.amountGrossVnd());
        charge.setCreatedBy(currentUser.id().orElse(null));
        charge.setCreatedAt(now());
        FolioCharge saved = charges.saveAndFlush(charge);

        changes.record(CHARGE, saved.getId(), saved.getVersion(), ChangeOp.CREATE);
        audit.record(CHARGE, saved.getId(), AUDIT_CHARGE_ADD, null, json(FolioChargeView.of(saved)));
        return view(reservation);
    }

    /**
     * Voids a charge entered by mistake (kept on the folio, excluded from the totals).
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404) for an unknown reservation or a
     *         charge that isn't of it; {@code HOTEL_INVALID_STATE} (409) unless {@code CHECKED_IN} or when the charge
     *         is already voided; {@code HOTEL_OVERPAYMENT} (409) when the payments already exceed the balance
     *         without it
     */
    @Transactional
    public FolioView voidCharge(UUID reservationId, UUID chargeId, VoidRequest request) {
        Reservation reservation = lock(reservationId);
        requireStatus(reservation, "Charges can only be voided while the stay is checked in", ReservationStatus.CHECKED_IN);
        FolioCharge charge = charges.findById(chargeId)
            .filter(found -> found.getReservationId().equals(reservationId))
            .orElseThrow(() -> HotelErrors.notFound("Charge not found"));
        if (charge.isVoided()) {
            throw HotelErrors.invalidState("The charge is already voided");
        }
        if (balance(reservation) - charge.getAmountGrossVnd() < 0) {
            throw HotelErrors.overpayment("Voiding this charge would leave the folio overpaid; void a payment first");
        }
        String before = json(FolioChargeView.of(charge));

        charge.setVoidedAt(now());
        charge.setVoidReason(request.reason().trim());
        FolioCharge saved = charges.saveAndFlush(charge);

        changes.record(CHARGE, saved.getId(), saved.getVersion(), ChangeOp.UPDATE);
        audit.record(CHARGE, saved.getId(), AUDIT_CHARGE_VOID, before, json(FolioChargeView.of(saved)));
        return view(reservation);
    }

    /**
     * Adds a payment (also an OTA prepayment on a confirmed booking).
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_INVALID_PAYMENT} (400) unless an OTA channel is
     *         given exactly for the {@code OTA} method; {@code HOTEL_NOT_FOUND} (404); {@code HOTEL_INVALID_STATE}
     *         (409) unless {@code CONFIRMED} or {@code CHECKED_IN}; {@code HOTEL_OVERPAYMENT} (409) when the amount
     *         exceeds the balance
     */
    @Transactional
    public FolioView addPayment(UUID reservationId, FolioPaymentRequest request) {
        if ((request.method() == PaymentMethod.OTA) != (request.otaChannel() != null)) {
            throw HotelErrors.invalidPayment(request.method() == PaymentMethod.OTA
                ? "An OTA payment needs the otaChannel that collected it"
                : "otaChannel is only for OTA payments");
        }
        Reservation reservation = lock(reservationId);
        requireOpenForPayments(reservation, "Payments can only be added to a confirmed or checked-in stay");
        long balance = balance(reservation);
        if (request.amountGrossVnd() > balance) {
            throw HotelErrors.overpayment("The payment of " + request.amountGrossVnd()
                + " VND exceeds the balance of " + balance + " VND");
        }

        FolioPayment payment = new FolioPayment();
        payment.setReservationId(reservationId);
        payment.setMethod(request.method());
        payment.setOtaChannel(request.otaChannel());
        payment.setReference(request.reference() == null || request.reference().isBlank()
            ? null : request.reference().trim());
        payment.setAmountGrossVnd(request.amountGrossVnd());
        payment.setCreatedBy(currentUser.id().orElse(null));
        payment.setCreatedAt(now());
        FolioPayment saved = payments.saveAndFlush(payment);

        changes.record(PAYMENT, saved.getId(), saved.getVersion(), ChangeOp.CREATE);
        audit.record(PAYMENT, saved.getId(), AUDIT_PAYMENT_ADD, null, json(FolioPaymentView.of(saved)));
        return view(reservation);
    }

    /**
     * Voids a payment entered by mistake (kept on the folio, excluded from the totals).
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404) for an unknown reservation or a
     *         payment that isn't of it; {@code HOTEL_INVALID_STATE} (409) unless {@code CONFIRMED} or
     *         {@code CHECKED_IN} or when the payment is already voided
     */
    @Transactional
    public FolioView voidPayment(UUID reservationId, UUID paymentId, VoidRequest request) {
        Reservation reservation = lock(reservationId);
        requireOpenForPayments(reservation, "Payments can only be voided while the stay is confirmed or checked in");
        FolioPayment payment = payments.findById(paymentId)
            .filter(found -> found.getReservationId().equals(reservationId))
            .orElseThrow(() -> HotelErrors.notFound("Payment not found"));
        if (payment.isVoided()) {
            throw HotelErrors.invalidState("The payment is already voided");
        }
        String before = json(FolioPaymentView.of(payment));

        payment.setVoidedAt(now());
        payment.setVoidReason(request.reason().trim());
        FolioPayment saved = payments.saveAndFlush(payment);

        changes.record(PAYMENT, saved.getId(), saved.getVersion(), ChangeOp.UPDATE);
        audit.record(PAYMENT, saved.getId(), AUDIT_PAYMENT_VOID, before, json(FolioPaymentView.of(saved)));
        return view(reservation);
    }

    /** The reservation with its row locked, before anything of its folio is read or written. */
    private Reservation lock(UUID reservationId) {
        return reservations.lockById(reservationId).orElseThrow(() -> HotelErrors.notFound("Reservation not found"));
    }

    private static void requireStatus(Reservation reservation, String rule, ReservationStatus allowed) {
        if (reservation.getStatus() != allowed) {
            throw HotelErrors.invalidState(rule + "; this stay is " + reservation.getStatus());
        }
    }

    private static void requireOpenForPayments(Reservation reservation, String rule) {
        if (reservation.getStatus() != ReservationStatus.CONFIRMED
                && reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw HotelErrors.invalidState(rule + "; this stay is " + reservation.getStatus());
        }
    }

    private long balance(Reservation reservation) {
        return FolioMath.balance(reservation.getTotalGrossVnd(),
            charges.sumActive(reservation.getId()), payments.sumActive(reservation.getId()));
    }

    private FolioView view(Reservation reservation) {
        List<FolioChargeView> chargeViews = charges.findByReservationIdOrderByCreatedAtAscIdAsc(reservation.getId())
            .stream().map(FolioChargeView::of).toList();
        List<FolioPaymentView> paymentViews = payments.findByReservationIdOrderByCreatedAtAscIdAsc(reservation.getId())
            .stream().map(FolioPaymentView::of).toList();
        long chargesTotal = chargeViews.stream().filter(line -> line.voidedAt() == null)
            .mapToLong(FolioChargeView::amountGrossVnd).reduce(0L, Math::addExact);
        long paymentsTotal = paymentViews.stream().filter(line -> line.voidedAt() == null)
            .mapToLong(FolioPaymentView::amountGrossVnd).reduce(0L, Math::addExact);
        return new FolioView(reservation.getId(), reservation.getStatus().name(), reservation.getTotalGrossVnd(),
            chargeViews, paymentViews, chargesTotal, paymentsTotal,
            FolioMath.balance(reservation.getTotalGrossVnd(), chargesTotal, paymentsTotal));
    }

    /** Microsecond precision, as stored by {@code timestamptz}, so responses and later reads agree. */
    private Instant now() {
        return Instant.now(hotelClock).truncatedTo(ChronoUnit.MICROS);
    }

    private String json(Object view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A folio line could not be serialized for the audit log", e);
        }
    }
}
