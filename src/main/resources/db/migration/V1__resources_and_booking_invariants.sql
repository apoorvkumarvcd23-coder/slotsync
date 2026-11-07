CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE resource_types (
    code varchar(32) PRIMARY KEY,
    display_name varchar(100) NOT NULL
);

CREATE TABLE resources (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
    display_name varchar(160) NOT NULL CHECK (length(trim(display_name)) > 0),
    type_code varchar(32) NOT NULL REFERENCES resource_types(code),
    location varchar(200) NOT NULL CHECK (length(trim(location)) > 0),
    capacity integer NOT NULL CHECK (capacity > 0),
    active boolean NOT NULL DEFAULT true
);

-- Login and generated demo credentials are a subsequent core slice.
CREATE TABLE users (
    id uuid PRIMARY KEY,
    username varchar(100) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('REQUESTER', 'APPROVER', 'ADMIN')),
    enabled boolean NOT NULL DEFAULT true
);

CREATE TABLE bookings (
    id uuid PRIMARY KEY,
    resource_id uuid NOT NULL REFERENCES resources(id),
    owner_id uuid NOT NULL REFERENCES users(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    CONSTRAINT valid_booking_interval CHECK (
        isfinite(starts_at) AND isfinite(ends_at) AND starts_at < ends_at
    ),
    CONSTRAINT no_overlapping_active_bookings EXCLUDE USING gist (
        resource_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('PENDING', 'APPROVED'))
);

CREATE INDEX bookings_owner_idx ON bookings(owner_id, starts_at);
