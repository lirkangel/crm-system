package com.crm.hotel.checkin;

import com.crm.foundation.Plugin.AuditRecorder;
import com.crm.foundation.Plugin.CurrentUser;
import com.crm.hotel.BusinessDay;
import com.crm.hotel.GuestSite;
import com.crm.hotel.HotelErrors;
import com.crm.hotel.domain.Reservation;
import com.crm.hotel.domain.ReservationStatus;
import com.crm.hotel.repo.GuestLinkRepository;
import com.crm.hotel.repo.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Personal check-in links. The token is 32 random bytes, base64url without padding; only its SHA-256 is stored, and
 * the token and URL are never logged or audited.
 */
@Service
@RequiredArgsConstructor
public class GuestLinkService {

    static final String RESERVATION = "Reservation";
    static final String AUDIT_CREATE = "GUEST_LINK_CREATE";
    static final String AUDIT_REVOKE = "GUEST_LINK_REVOKE";

    private static final SecureRandom RANDOM = new SecureRandom();

    // The field name doubles as the bean name, which disambiguates from any Clock in the parent context.
    private final Clock hotelClock;
    private final BusinessDay businessDay;
    private final GuestSite guestSite;
    private final ReservationRepository reservations;
    private final GuestLinkRepository links;
    private final CurrentUser currentUser;
    private final AuditRecorder audit;

    /** The link to hand to the guest (shown once) and when it stops working. */
    public record CreatedLink(String url, Instant expiresAt) {
    }

    /**
     * Makes the reservation's link, replacing (and so killing) any earlier one.
     *
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404); {@code HOTEL_INVALID_STATE}
     *         (409) unless the reservation is {@code CONFIRMED}
     */
    @Transactional
    public CreatedLink create(UUID reservationId) {
        Reservation reservation = reservations.lockById(reservationId)
            .orElseThrow(() -> HotelErrors.notFound("Reservation not found"));
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw HotelErrors.invalidState(
                "A check-in link can only be made for a confirmed reservation; this one is " + reservation.getStatus());
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // 06:00 hotel time (the business day cutoff) on the day after the check-out date
        Instant expiresAt = businessDay.cutoffInstantOn(reservation.getCheckOut().plusDays(1));

        GuestLink link = links.findById(reservationId).orElseGet(GuestLink::new);
        link.setReservationId(reservationId);
        link.setTokenHash(sha256(token));
        link.setCreatedBy(currentUser.id().orElse(null));
        // Microsecond precision, as stored by timestamptz
        link.setCreatedAt(Instant.now(hotelClock).truncatedTo(ChronoUnit.MICROS));
        link.setExpiresAt(expiresAt);
        links.saveAndFlush(link);
        audit.record(RESERVATION, reservationId, AUDIT_CREATE, null, "{\"expiresAt\":\"" + expiresAt + "\"}");
        return new CreatedLink(guestSite.baseUrl() + "/#" + token, expiresAt);
    }

    /**
     * @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404) if the reservation has no link
     */
    @Transactional
    public void revoke(UUID reservationId) {
        reservations.lockById(reservationId).orElseThrow(() -> HotelErrors.notFound("Reservation not found"));
        if (links.deleteByReservation(reservationId) == 0) {
            throw HotelErrors.notFound("No check-in link for this reservation");
        }
        audit.record(RESERVATION, reservationId, AUDIT_REVOKE, null, null);
    }

    /** @throws com.crm.foundation.Plugin.PluginProblem {@code HOTEL_NOT_FOUND} (404) for an unknown reservation */
    @Transactional(readOnly = true)
    public GuestLinkView status(UUID reservationId) {
        if (!reservations.existsById(reservationId)) {
            throw HotelErrors.notFound("Reservation not found");
        }
        return links.findById(reservationId)
            .map(link -> new GuestLinkView(true, link.getCreatedAt(), link.getExpiresAt()))
            .orElseGet(GuestLinkView::none);
    }

    /**
     * The reservation a guest's token opens. The reservation's status is not checked here (a closed link is the
     * caller's {@code HOTEL_CHECKIN_CLOSED}).
     *
     * @throws com.crm.foundation.Plugin.PluginProblem the same {@code HOTEL_NOT_FOUND} (404) for an unknown,
     *         replaced, revoked or expired token
     */
    @Transactional(readOnly = true)
    public Reservation resolve(String token) {
        GuestLink link = links.findByTokenHash(sha256(token == null ? "" : token))
            .orElseThrow(() -> HotelErrors.notFound("Link not found"));
        if (!Instant.now(hotelClock).isBefore(link.getExpiresAt())) {
            throw HotelErrors.notFound("Link not found");
        }
        return reservations.findById(link.getReservationId())
            .orElseThrow(() -> HotelErrors.notFound("Link not found"));
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
