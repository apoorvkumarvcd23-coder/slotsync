package dev.slotsync;

import org.springframework.jdbc.core.JdbcTemplate;

final class TestData {
    private TestData() {}
    static void reset(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM admin_audit_events");
        jdbc.update("DELETE FROM import_batches");
        jdbc.update("DELETE FROM audit_events");
        jdbc.update("DELETE FROM idempotency_requests");
        jdbc.update("DELETE FROM bookings");
        jdbc.update("UPDATE policy_current SET version_id=1");
        jdbc.update("DELETE FROM policy_versions WHERE id<>1");
        jdbc.update("DELETE FROM resources WHERE id NOT IN ('10000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000003')");
        jdbc.update("DELETE FROM users");
    }
}
