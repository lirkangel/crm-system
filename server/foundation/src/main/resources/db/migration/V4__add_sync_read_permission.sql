-- ─────────────── core.sync.read: pull the change-event delta feed ───────────

INSERT INTO permissions (id, key, plugin_id, description) VALUES
    (gen_random_uuid(), 'core.sync.read', NULL, 'Pull change-event delta feed');

-- admin gets every built-in permission (same rule as V3)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.key = 'core.sync.read'
WHERE r.code = 'admin';
