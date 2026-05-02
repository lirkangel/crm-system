-- Foundation core schema.
-- Convention: all Foundation tables live in the 'public' schema.
-- Plugin tables live in their own schema (e.g., 'hotel', 'dental').

-- ─────────────────────────────── identity ───────────────────────────────────

CREATE TABLE users (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(64) NOT NULL,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    locked_until    TIMESTAMPTZ,
    failed_logins   INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email    UNIQUE (email)
);

CREATE TABLE roles (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64) NOT NULL,
    name_i18n   JSONB       NOT NULL,       -- e.g. {"vi":"Quản trị","en":"Admin"}
    builtin     BOOLEAN     NOT NULL DEFAULT FALSE,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_roles_code UNIQUE (code)
);

CREATE TABLE permissions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    key         VARCHAR(255) NOT NULL,      -- e.g. hotel.checkin.perform
    plugin_id   VARCHAR(255),               -- NULL for built-in Foundation perms
    description TEXT,
    CONSTRAINT uq_permissions_key UNIQUE (key)
);

CREATE TABLE role_permissions (
    role_id         UUID NOT NULL REFERENCES roles(id)       ON DELETE CASCADE,
    permission_id   UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ──────────────────────────── refresh tokens ────────────────────────────────

CREATE TABLE refresh_tokens (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    jti             UUID        NOT NULL,           -- JWT ID claim, single-use
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    replaced_by     UUID,                           -- points to successor jti
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_refresh_tokens_jti UNIQUE (jti)
);

CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens(user_id);

-- ─────────────────────────── password reset ─────────────────────────────────

CREATE TABLE password_reset_tokens (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    jti         UUID        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    CONSTRAINT uq_prt_jti UNIQUE (jti)
);

-- ─────────────────────────────── audit log ──────────────────────────────────

CREATE TABLE audit_event (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    user_id         UUID,                           -- NULL for system events
    source_ip       INET,
    plugin_id       VARCHAR(255),                   -- 'foundation' or plugin id
    entity_type     VARCHAR(255),
    entity_id       UUID,
    op              VARCHAR(64) NOT NULL,            -- LOGIN, LOGIN_FAIL, INSERT, ...
    before_json     JSONB,
    after_json      JSONB,
    severity        VARCHAR(16) NOT NULL DEFAULT 'INFO',  -- INFO|WARN|CRITICAL
    prev_hash       BYTEA       NOT NULL,           -- 32 bytes, zeros for first row
    hash            BYTEA       NOT NULL            -- SHA-256(prev_hash || payload)
);

CREATE INDEX ix_audit_event_occurred_at     ON audit_event(occurred_at DESC);
CREATE INDEX ix_audit_event_entity          ON audit_event(entity_type, entity_id);
CREATE INDEX ix_audit_event_user_id         ON audit_event(user_id);
CREATE INDEX ix_audit_event_severity        ON audit_event(severity) WHERE severity != 'INFO';

-- ─────────────────────────── change events (sync) ───────────────────────────

CREATE TABLE change_event (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_id       VARCHAR(255) NOT NULL,
    entity_type     VARCHAR(255) NOT NULL,
    entity_id       UUID        NOT NULL,
    version         BIGINT      NOT NULL,
    op              VARCHAR(16) NOT NULL,            -- INSERT|UPDATE|DELETE
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_change_event_occurred_at    ON change_event(occurred_at DESC);
CREATE INDEX ix_change_event_plugin_entity  ON change_event(plugin_id, entity_type);

-- ─────────────────────────────── plugins ────────────────────────────────────

CREATE TABLE plugin_registry (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_id       VARCHAR(255) NOT NULL,
    version         VARCHAR(64) NOT NULL,
    schema_name     VARCHAR(64) NOT NULL,
    state           VARCHAR(32) NOT NULL,   -- ACTIVE|DISABLED|LOAD_FAILED|UNINSTALL_PENDING
    last_loaded_at  TIMESTAMPTZ,
    error_message   TEXT,
    manifest_json   JSONB       NOT NULL,
    CONSTRAINT uq_plugin_registry_plugin_id  UNIQUE (plugin_id),
    CONSTRAINT uq_plugin_registry_schema     UNIQUE (schema_name)
);

-- ─────────────────────────────── files ──────────────────────────────────────

CREATE TABLE attachment (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_id       VARCHAR(255) NOT NULL,
    owner_user_id   UUID        REFERENCES users(id) ON DELETE SET NULL,
    filename        VARCHAR(255) NOT NULL,
    content_type    VARCHAR(128),
    size_bytes      BIGINT      NOT NULL,
    storage_path    TEXT        NOT NULL,
    sha256          BYTEA       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_attachment_plugin_id ON attachment(plugin_id);

-- ─────────────────────────── seed: built-in roles ───────────────────────────

INSERT INTO roles (id, code, name_i18n, builtin) VALUES
    (gen_random_uuid(), 'admin',        '{"vi":"Quản trị","en":"Admin"}',          TRUE),
    (gen_random_uuid(), 'manager',      '{"vi":"Quản lý","en":"Manager"}',         TRUE),
    (gen_random_uuid(), 'receptionist', '{"vi":"Lễ tân","en":"Receptionist"}',     TRUE),
    (gen_random_uuid(), 'housekeeping', '{"vi":"Buồng phòng","en":"Housekeeping"}',TRUE),
    (gen_random_uuid(), 'viewer',       '{"vi":"Xem","en":"Viewer"}',              TRUE);

-- ─────────────── seed: built-in Foundation permissions ──────────────────────

INSERT INTO permissions (id, key, plugin_id, description) VALUES
    (gen_random_uuid(), 'core.users.read',   NULL, 'View user list and profiles'),
    (gen_random_uuid(), 'core.users.write',  NULL, 'Create and edit users'),
    (gen_random_uuid(), 'core.users.delete', NULL, 'Disable or delete users'),
    (gen_random_uuid(), 'core.roles.read',   NULL, 'View roles and permissions'),
    (gen_random_uuid(), 'core.roles.write',  NULL, 'Create and compose roles'),
    (gen_random_uuid(), 'core.audit.read',   NULL, 'View audit log'),
    (gen_random_uuid(), 'core.plugins.manage', NULL, 'Install, disable, uninstall plugins');
