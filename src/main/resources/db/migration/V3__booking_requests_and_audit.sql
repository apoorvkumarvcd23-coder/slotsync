CREATE TABLE idempotency_requests (
    actor_id uuid NOT NULL REFERENCES users(id),
    request_key varchar(100) NOT NULL,
    request_hash varchar(64) NOT NULL,
    booking_id uuid NOT NULL REFERENCES bookings(id),
    response_json text NOT NULL,
    PRIMARY KEY (actor_id, request_key)
);

CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES users(id),
    booking_id uuid NOT NULL REFERENCES bookings(id),
    action varchar(24) NOT NULL,
    previous_status varchar(16),
    new_status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE INDEX audit_events_booking_idx ON audit_events(booking_id, created_at);
