-- Dental plugin: patients, phone appointments, pre-visit links, CCCD photos and the pre-visit form.
-- Health answers (allergies, medicines, reason) are stored encrypted (IV || AES-GCM ciphertext) and the
-- identity and health values never reach the audit log or change feed.
-- Flyway runs this with the plugin schema ("dental") as its default schema.

CREATE TABLE patient (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name     VARCHAR(200) NOT NULL,
    name_search   TEXT         NOT NULL,                -- accent-folded, lower-cased full_name for search
    phone         VARCHAR(20)  NOT NULL,                -- +84...
    date_of_birth DATE         NOT NULL,
    id_type       VARCHAR(16),
    id_number     VARCHAR(64),
    nationality   CHAR(2),                              -- ISO 3166-1 alpha-2
    address       VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT patient_id_type_valid CHECK (id_type IS NULL OR id_type IN ('CCCD', 'PASSPORT', 'OTHER'))
);

-- Several patients may share one phone (a parent booking for a child), so the phone is not unique
CREATE INDEX patient_phone_idx ON patient (phone);

CREATE TABLE appointment (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id       UUID         NOT NULL REFERENCES patient (id),
    starts_at        TIMESTAMPTZ  NOT NULL,
    duration_minutes INT          NOT NULL DEFAULT 30,
    dentist          VARCHAR(200),
    reason           VARCHAR(500),
    status           VARCHAR(16)  NOT NULL,
    created_by       UUID,                              -- foundation user id; no FK across schemas
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT appointment_status_valid CHECK (status IN ('BOOKED', 'ARRIVED', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT appointment_duration_positive CHECK (duration_minutes > 0)
);

CREATE INDEX appointment_patient_idx ON appointment (patient_id);
CREATE INDEX appointment_starts_at_idx ON appointment (starts_at);

-- One pre-visit link per appointment; only the SHA-256 of the token is stored
CREATE TABLE visit_link (
    appointment_id UUID PRIMARY KEY REFERENCES appointment (id) ON DELETE CASCADE,
    token_hash     BYTEA       NOT NULL UNIQUE,
    created_by     UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    dob_attempts   INT         NOT NULL DEFAULT 0
);

-- CCCD photos: the downscaled QR side of a citizen ID card, purged 30 days after upload
CREATE TABLE id_scan (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id UUID        NOT NULL REFERENCES appointment (id) ON DELETE CASCADE,
    content_type   VARCHAR(32) NOT NULL,
    image          BYTEA       NOT NULL,
    sha256         BYTEA       NOT NULL,                -- of the ORIGINAL upload
    qr_digest      VARCHAR(600),                        -- digest of the decoded QR payload, for matching the form
    size_bytes     INT         NOT NULL,                -- of the stored image
    uploaded_by    UUID,
    uploaded_at    TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT id_scan_appointment_sha_unique UNIQUE (appointment_id, sha256)
);

CREATE INDEX id_scan_uploaded_at_idx ON id_scan (uploaded_at);
CREATE INDEX id_scan_appointment_idx ON id_scan (appointment_id);

CREATE TABLE previsit_form (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id UUID         NOT NULL UNIQUE REFERENCES appointment (id) ON DELETE CASCADE,
    patient_id     UUID         NOT NULL REFERENCES patient (id),
    full_name      VARCHAR(200),
    date_of_birth  DATE,
    id_type        VARCHAR(16),
    id_number      VARCHAR(64),
    nationality    CHAR(2),
    address        VARCHAR(500),
    parent_name    VARCHAR(200),                        -- required for a child under 14 (checked in code)
    parent_phone   VARCHAR(20),
    phone          VARCHAR(20),
    allergies_enc  BYTEA,                               -- IV || AES-GCM ciphertext
    medicines_enc  BYTEA,
    reason_enc     BYTEA,
    id_scan_id     UUID UNIQUE REFERENCES id_scan (id) ON DELETE SET NULL,
    edited_fields  TEXT,
    consent_at     TIMESTAMPTZ  NOT NULL,
    notice_version VARCHAR(16)  NOT NULL,
    submitted_at   TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT previsit_form_id_type_valid CHECK (id_type IS NULL OR id_type IN ('CCCD', 'PASSPORT', 'OTHER'))
);

CREATE INDEX previsit_form_patient_idx ON previsit_form (patient_id);
