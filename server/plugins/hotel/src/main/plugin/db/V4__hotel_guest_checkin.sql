-- Online check-in: a personal link per confirmed reservation (only the SHA-256 of its token is stored) and the
-- identity details a guest sends before arriving. Pre-arrival rows are personal data: never in the audit log or
-- change feed, deleted at check-in, at cancellation and 30 days after submission.
-- Flyway runs this with the plugin schema ("hotel") as its default schema.

CREATE TABLE guest_link (
    reservation_id UUID        PRIMARY KEY REFERENCES reservation (id),
    token_hash     BYTEA       NOT NULL UNIQUE,         -- SHA-256 of the 32-byte token; the token itself is never stored
    created_by     UUID,                                -- foundation user id; no FK across schemas
    created_at     TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL
);

CREATE TABLE pre_arrival_guest (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID         NOT NULL REFERENCES reservation (id),
    id_type        VARCHAR(16)  NOT NULL,
    id_number      VARCHAR(64)  NOT NULL,
    full_name      VARCHAR(200) NOT NULL,
    date_of_birth  DATE         NOT NULL,
    nationality    CHAR(2)      NOT NULL,               -- ISO 3166-1 alpha-2
    address        VARCHAR(500),
    visa_number    VARCHAR(64),
    entry_date     DATE,
    -- A scan can be purged while its younger submission stays: the photo expires, the typed details remain
    id_scan_id     UUID         UNIQUE REFERENCES id_scan (id) ON DELETE SET NULL,
    edited_fields  VARCHAR(200),                        -- comma-separated names of fields the guest changed after the scan
    consent_at     TIMESTAMPTZ  NOT NULL,
    notice_version VARCHAR(16)  NOT NULL,
    submitted_at   TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pre_arrival_guest_id_type_valid CHECK (id_type IN ('CCCD', 'PASSPORT', 'OTHER')),
    CONSTRAINT pre_arrival_guest_document_unique UNIQUE (reservation_id, id_type, id_number)
);

CREATE INDEX pre_arrival_guest_reservation_idx ON pre_arrival_guest (reservation_id);
CREATE INDEX pre_arrival_guest_submitted_at_idx ON pre_arrival_guest (submitted_at);

ALTER TABLE id_scan ADD COLUMN qr_digest VARCHAR(600);
