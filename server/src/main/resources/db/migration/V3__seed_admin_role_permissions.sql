-- ─────────────── seed: admin role maps to all built-in permissions ──────────

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'admin'
  AND p.plugin_id IS NULL;
