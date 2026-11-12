CREATE TABLE policy_versions (
    id bigserial PRIMARY KEY,
    max_duration_minutes integer NOT NULL CHECK (max_duration_minutes BETWEEN 1 AND 1440),
    approval_required boolean NOT NULL,
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);
INSERT INTO policy_versions(max_duration_minutes,approval_required) VALUES (1440,true);
CREATE TABLE policy_current (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    version_id bigint NOT NULL REFERENCES policy_versions(id)
);
INSERT INTO policy_current VALUES (true,1);
-- Old reservations keep the original 24-hour/approval policy.
ALTER TABLE bookings ADD COLUMN policy_version_id bigint NOT NULL DEFAULT 1 REFERENCES policy_versions(id);

CREATE TABLE import_batches (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES users(id),
    input_hash varchar(64) NOT NULL,
    preview_json text NOT NULL,
    valid boolean NOT NULL,
    applied_at timestamptz,
    applied_count integer CHECK(applied_count BETWEEN 1 AND 100),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE(actor_id,input_hash),
    CHECK ((applied_at IS NULL) = (applied_count IS NULL))
);
CREATE TABLE admin_audit_events (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES users(id),
    action varchar(24) NOT NULL,
    entity_id varchar(100) NOT NULL,
    details_json text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE INDEX admin_audit_time_idx ON admin_audit_events(created_at);
