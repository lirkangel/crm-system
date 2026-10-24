-- Hotel stay slice: check-in/check-out lifecycle, booking source, identity records and the folio.
-- Flyway runs this with the plugin schema ("hotel") as its default schema.

-- Booking source ------------------------------------------------------------------------------
ALTER TABLE reservation
    ADD COLUMN source         VARCHAR(16) NOT NULL DEFAULT 'WALK_IN',
    ADD COLUMN ota_channel    VARCHAR(16),
    ADD COLUMN ota_reference  VARCHAR(64),
    ADD COLUMN checked_in_at  TIMESTAMPTZ,
    ADD COLUMN checked_out_at TIMESTAMPTZ;

ALTER TABLE reservation
    ADD CONSTRAINT reservation_source_valid CHECK (source IN ('WALK_IN', 'PHONE', 'OTA', 'OTHER')),
    ADD CONSTRAINT reservation_ota_channel_valid CHECK (
        ota_channel IS NULL OR ota_channel IN ('BOOKING_COM', 'AGODA', 'TRIP_COM', 'EXPEDIA', 'TRAVELOKA', 'OTHER')),
    -- an OTA booking always names its channel, any other booking never does
    ADD CONSTRAINT reservation_ota_channel_iff_ota CHECK ((source = 'OTA') = (ota_channel IS NOT NULL));

-- Lifecycle: CONFIRMED -> CHECKED_IN -> CHECKED_OUT, CONFIRMED -> CANCELLED ------------------------
ALTER TABLE reservation DROP CONSTRAINT reservation_status_valid;
ALTER TABLE reservation
    ADD CONSTRAINT reservation_status_valid
        CHECK (status IN ('CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED'));

-- Occupancy guarantee: a room with a checked-in stay stays blocked like a confirmed one.
-- CHECKED_OUT and CANCELLED stays never block.
ALTER TABLE reservation DROP CONSTRAINT reservation_no_overlap;
ALTER TABLE reservation
    ADD CONSTRAINT reservation_no_overlap EXCLUDE USING gist (
        room_id WITH =,
        daterange(check_in, check_out, '[)') WITH &&
    ) WHERE (status IN ('CONFIRMED', 'CHECKED_IN'));

-- Identity records (one per adult per stay; personal data, never written to the audit log) ------
CREATE TABLE stay_guest (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID         NOT NULL REFERENCES reservation (id),
    id_type        VARCHAR(16)  NOT NULL,
    id_number      VARCHAR(64)  NOT NULL,
    full_name      VARCHAR(200) NOT NULL,
    date_of_birth  DATE         NOT NULL,
    nationality    CHAR(2)      NOT NULL,           -- ISO 3166-1 alpha-2
    address        VARCHAR(500),                    -- required for Vietnamese citizens (checked in code)
    visa_number    VARCHAR(64),
    entry_date     DATE,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT stay_guest_id_type_valid CHECK (id_type IN ('CCCD', 'PASSPORT', 'OTHER'))
);

CREATE INDEX stay_guest_reservation_idx ON stay_guest (reservation_id);

-- Folio: extra charges and payments; voided instead of deleted --------------------------------
CREATE TABLE folio_charge (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id   UUID         NOT NULL REFERENCES reservation (id),
    description      VARCHAR(200) NOT NULL,
    amount_gross_vnd BIGINT       NOT NULL,
    created_by       UUID,                          -- foundation user id; no FK across schemas
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    voided_at        TIMESTAMPTZ,
    void_reason      VARCHAR(500),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT folio_charge_amount_positive CHECK (amount_gross_vnd > 0)
);

CREATE INDEX folio_charge_reservation_idx ON folio_charge (reservation_id);

CREATE TABLE folio_payment (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id   UUID         NOT NULL REFERENCES reservation (id),
    method           VARCHAR(16)  NOT NULL,
    ota_channel      VARCHAR(16),
    reference        VARCHAR(100),
    amount_gross_vnd BIGINT       NOT NULL,
    created_by       UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    voided_at        TIMESTAMPTZ,
    void_reason      VARCHAR(500),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT folio_payment_amount_positive CHECK (amount_gross_vnd > 0),
    CONSTRAINT folio_payment_method_valid CHECK (method IN ('CASH', 'CARD', 'BANK_TRANSFER', 'OTA')),
    CONSTRAINT folio_payment_ota_channel_valid CHECK (
        ota_channel IS NULL OR ota_channel IN ('BOOKING_COM', 'AGODA', 'TRIP_COM', 'EXPEDIA', 'TRAVELOKA', 'OTHER')),
    CONSTRAINT folio_payment_ota_channel_iff_ota CHECK ((method = 'OTA') = (ota_channel IS NOT NULL))
);

CREATE INDEX folio_payment_reservation_idx ON folio_payment (reservation_id);
