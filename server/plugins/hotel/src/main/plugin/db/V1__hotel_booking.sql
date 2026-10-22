-- Hotel booking slice. Flyway runs this with the plugin schema ("hotel") as its default schema.

-- Needed for the no-overlap exclusion constraint (uuid equality inside a GiST index).
-- Kept in public: the extension is shared by name and must not vanish with the hotel schema.
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

CREATE TABLE room_type (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                   VARCHAR(32)  NOT NULL,
    name_i18n              JSONB        NOT NULL,
    max_guests             INT          NOT NULL,
    nightly_rate_gross_vnd BIGINT       NOT NULL,   -- whole VND, VAT and service charge included
    active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT room_type_code_key UNIQUE (code),
    CONSTRAINT room_type_max_guests_positive CHECK (max_guests > 0),
    CONSTRAINT room_type_rate_non_negative CHECK (nightly_rate_gross_vnd >= 0)
);

CREATE TABLE room (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    number       VARCHAR(16) NOT NULL,
    room_type_id UUID        NOT NULL REFERENCES room_type (id),
    floor        INT,
    in_service   BOOLEAN     NOT NULL DEFAULT TRUE,
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT room_number_key UNIQUE (number)
);

CREATE INDEX room_room_type_idx ON room (room_type_id);

CREATE TABLE guest (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name  VARCHAR(200) NOT NULL,
    phone      VARCHAR(32)  NOT NULL,               -- not unique: families and tour groups share one
    email      VARCHAR(254),
    search_key VARCHAR(500) NOT NULL,               -- accent-folded, lower-cased name + phone for search
    version    BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX guest_search_key_idx ON guest (search_key);

CREATE SEQUENCE reservation_code_seq;

CREATE TABLE reservation (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                   VARCHAR(20)  NOT NULL,
    guest_id               UUID         NOT NULL REFERENCES guest (id),
    room_id                UUID         NOT NULL REFERENCES room (id),
    check_in               DATE         NOT NULL,
    check_out              DATE         NOT NULL,   -- a stay is [check_in, check_out)
    adults                 INT          NOT NULL,
    status                 VARCHAR(16)  NOT NULL,
    nightly_rate_gross_vnd BIGINT       NOT NULL,   -- snapshot of the room type rate at booking
    total_gross_vnd        BIGINT       NOT NULL,
    notes                  TEXT,
    created_by             UUID,                    -- foundation user id; no FK across schemas
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cancelled_at           TIMESTAMPTZ,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT reservation_code_key UNIQUE (code),
    CONSTRAINT reservation_stay_valid CHECK (check_out > check_in),
    CONSTRAINT reservation_adults_positive CHECK (adults > 0),
    CONSTRAINT reservation_status_valid CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT reservation_amounts_non_negative CHECK (nightly_rate_gross_vnd >= 0 AND total_gross_vnd >= 0),
    -- No double booking: confirmed stays of one room never overlap. This, not the application
    -- check, is what guarantees correctness under concurrent requests.
    CONSTRAINT reservation_no_overlap EXCLUDE USING gist (
        room_id WITH =,
        daterange(check_in, check_out, '[)') WITH &&
    ) WHERE (status = 'CONFIRMED')
);

CREATE INDEX reservation_guest_idx ON reservation (guest_id);
CREATE INDEX reservation_stay_idx ON reservation (check_in, check_out);
