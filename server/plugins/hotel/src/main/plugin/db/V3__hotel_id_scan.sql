-- CCCD scans: the downscaled photo of the QR side of a citizen ID card, deleted 30 days after upload (hourly
-- purge) or when its identity record is undone or replaced. Personal data: never in the audit log or change feed.
-- Flyway runs this with the plugin schema ("hotel") as its default schema.

CREATE TABLE id_scan (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID        NOT NULL REFERENCES reservation (id),
    stay_guest_id  UUID        UNIQUE REFERENCES stay_guest (id),  -- set when the scan is linked to an adult
    content_type   VARCHAR(32) NOT NULL,
    image          BYTEA       NOT NULL,
    sha256         BYTEA       NOT NULL,                           -- of the ORIGINAL upload
    size_bytes     INT         NOT NULL,                           -- of the stored image
    uploaded_by    UUID,                                           -- foundation user id; no FK across schemas
    uploaded_at    TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT id_scan_reservation_sha_unique UNIQUE (reservation_id, sha256)
);

CREATE INDEX id_scan_uploaded_at_idx ON id_scan (uploaded_at);
CREATE INDEX id_scan_reservation_idx ON id_scan (reservation_id);
