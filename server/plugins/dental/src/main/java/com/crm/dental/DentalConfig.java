package com.crm.dental;

import com.crm.dental.crypto.FieldCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Base64;

/** Beans of the dental plugin's child context. */
@Configuration(proxyBeanMethods = false)
public class DentalConfig {

    /** Clinic time: "today" and the link expiry are the local date here, not the server's. */
    public static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Injected wherever the current time matters, so tests can pin it. */
    @Bean
    Clock dentalClock() {
        return Clock.system(CLINIC_ZONE);
    }

    /** The public patient route ships switched off; base URL and clinic name feed the link and the page. */
    @Bean
    DentalSite dentalSite(
            @Value("${dental.public.enabled:${DENTAL_PUBLIC_ENABLED:false}}") boolean publicEnabled,
            @Value("${dental.guest-base-url:${DENTAL_GUEST_BASE_URL:https://nhakhoa.mathisterben.org}}") String baseUrl,
            @Value("${dental.display-name:${DENTAL_DISPLAY_NAME:Nha khoa}}") String displayName) {
        return new DentalSite(publicEnabled, baseUrl.trim().replaceAll("/+$", ""), displayName);
    }

    /**
     * The field cipher. A key (base64 of 32 bytes) is always validated when present. Without one the cipher is
     * disabled (throws on use) while the public route is off; enabling the route without a key fails the
     * plugin's start (LOAD_FAILED). Messages never echo the value.
     */
    @Bean
    FieldCipher fieldCipher(
            @Value("${dental.public.enabled:${DENTAL_PUBLIC_ENABLED:false}}") boolean publicEnabled,
            @Value("${dental.field-key:${DENTAL_FIELD_KEY:}}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            if (publicEnabled) {
                throw new IllegalStateException("DENTAL_FIELD_KEY is required when dental.public.enabled is true");
            }
            return FieldCipher.disabled();
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("DENTAL_FIELD_KEY must be base64 of exactly 32 bytes");
        }
        if (raw.length != FieldCipher.KEY_BYTES) {
            throw new IllegalStateException("DENTAL_FIELD_KEY must be base64 of exactly 32 bytes");
        }
        return new FieldCipher(raw);
    }
}
