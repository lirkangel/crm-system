package com.crm.dental.link;

import com.crm.dental.DentalConfig;
import com.crm.dental.DentalErrors;
import com.crm.dental.DentalSite;
import com.crm.dental.appointment.Appointment;
import com.crm.dental.appointment.AppointmentRepository;
import com.crm.dental.appointment.AppointmentStatus;
import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Personal pre-visit links. The token is 32 random bytes, base64url without padding; only its SHA-256 is stored, and
 * the token and URL are never logged or audited. The link expires at 23:59 clinic time on the appointment's date.
 */
@Service
@RequiredArgsConstructor
public class VisitLinkService {

    static final String APPOINTMENT = "Appointment";
    static final String AUDIT_CREATE = "VISIT_LINK_CREATE";
    static final String AUDIT_REVOKE = "VISIT_LINK_REVOKE";

    /** A header longer than this cannot be one of our tokens (43 characters): refused without hashing. */
    static final int MAX_TOKEN_LENGTH = 100;

    private static final SecureRandom RANDOM = new SecureRandom();

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock dentalClock;
    private final DentalSite dentalSite;
    private final AppointmentRepository appointments;
    private final VisitLinkRepository links;
    private final CurrentUser currentUser;
    private final AuditRecorder audit;

    /** The link to hand to the patient (shown once) and when it stops working. */
    public record CreatedLink(String url, Instant expiresAt) {
    }

    /** 23:59 clinic time on the clinic-local date of {@code startsAt}. */
    public static Instant expiryFor(Instant startsAt) {
        return startsAt.atZone(DentalConfig.CLINIC_ZONE).toLocalDate()
            .atTime(LocalTime.of(23, 59)).atZone(DentalConfig.CLINIC_ZONE).toInstant();
    }

    /**
     * Makes the appointment's link, replacing (and so killing) any earlier one.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_PUBLIC_DISABLED} (409) while the public route is
     *         off; {@code DENTAL_NOT_FOUND} (404); {@code DENTAL_INVALID_STATE} (409) unless the appointment is
     *         {@code BOOKED}; {@code DENTAL_VISIT_CLOSED} (409) when the link would already be expired
     */
    @Transactional
    public CreatedLink create(UUID appointmentId) {
        if (!dentalSite.publicEnabled()) {
            throw DentalErrors.publicDisabled("The public pre-visit route is switched off");
        }
        Appointment appointment = appointments.lockById(appointmentId)
            .orElseThrow(() -> DentalErrors.notFound("Appointment not found"));
        if (appointment.getStatus() != AppointmentStatus.BOOKED) {
            throw DentalErrors.invalidState(
                "A pre-visit link can only be made for a booked appointment; this one is " + appointment.getStatus());
        }
        Instant expiresAt = expiryFor(appointment.getStartsAt());
        if (!expiresAt.isAfter(Instant.now(dentalClock))) {
            throw DentalErrors.visitClosed("The visit day is over: a pre-visit link would already be expired");
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        VisitLink link = links.findById(appointmentId).orElseGet(VisitLink::new);
        link.setAppointmentId(appointmentId);
        link.setTokenHash(sha256(token));
        link.setCreatedBy(currentUser.id().orElse(null));
        // Microsecond precision, as stored by timestamptz
        link.setCreatedAt(Instant.now(dentalClock).truncatedTo(ChronoUnit.MICROS));
        link.setExpiresAt(expiresAt);
        link.setDobAttempts(0);
        links.saveAndFlush(link);
        audit.record(APPOINTMENT, appointmentId, AUDIT_CREATE, null, "{\"expiresAt\":\"" + expiresAt + "\"}");
        return new CreatedLink(dentalSite.guestBaseUrl() + "/#d/" + token, expiresAt);
    }

    /**
     * Works while the public route is off too.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_NOT_FOUND} (404) if the appointment has no link
     */
    @Transactional
    public void revoke(UUID appointmentId) {
        appointments.lockById(appointmentId).orElseThrow(() -> DentalErrors.notFound("Appointment not found"));
        if (links.deleteByAppointment(appointmentId) == 0) {
            throw DentalErrors.notFound("No pre-visit link for this appointment");
        }
        audit.record(APPOINTMENT, appointmentId, AUDIT_REVOKE, null, null);
    }

    /** @throws com.crm.foundation.Plugin.PluginProblem {@code DENTAL_NOT_FOUND} (404) for an unknown appointment */
    @Transactional(readOnly = true)
    public VisitLinkView status(UUID appointmentId) {
        if (!appointments.existsById(appointmentId)) {
            throw DentalErrors.notFound("Appointment not found");
        }
        return links.findById(appointmentId)
            .map(link -> new VisitLinkView(true, link.getCreatedAt(), link.getExpiresAt()))
            .orElseGet(VisitLinkView::none);
    }

    /**
     * The appointment a patient's token opens. The appointment's status is not checked here (a closed link is the
     * caller's {@code DENTAL_VISIT_CLOSED}).
     *
     * @throws com.crm.foundation.Plugin.PluginProblem the same {@code DENTAL_NOT_FOUND} (404) while the public route
     *         is off, and for a missing, blank, over-long, unknown, replaced, revoked or expired token
     */
    @Transactional(readOnly = true)
    public Appointment resolve(String token) {
        if (!dentalSite.publicEnabled() || token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw DentalErrors.notFound("Link not found");
        }
        VisitLink link = links.findByTokenHash(sha256(token))
            .orElseThrow(() -> DentalErrors.notFound("Link not found"));
        if (!Instant.now(dentalClock).isBefore(link.getExpiresAt())) {
            throw DentalErrors.notFound("Link not found");
        }
        return appointments.findById(link.getAppointmentId())
            .orElseThrow(() -> DentalErrors.notFound("Link not found"));
    }

    /** The SHA-256 under which a token's link is stored. */
    public static byte[] hashOf(String token) {
        return sha256(token);
    }

    /**
     * Inside the caller's locked transaction: the appointment's link, provided it still exists, still has the
     * token hash the request resolved with, and has not expired (a link revoked, regenerated or expired between
     * {@link #resolve} and the lock is the same 404 as any bad token).
     */
    public VisitLink requireLive(UUID appointmentId, byte[] tokenHash) {
        VisitLink link = links.findById(appointmentId).orElseThrow(() -> DentalErrors.notFound("Link not found"));
        if (tokenHash == null || !MessageDigest.isEqual(link.getTokenHash(), tokenHash)
                || !Instant.now(dentalClock).isBefore(link.getExpiresAt())) {
            throw DentalErrors.notFound("Link not found");
        }
        return link;
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
