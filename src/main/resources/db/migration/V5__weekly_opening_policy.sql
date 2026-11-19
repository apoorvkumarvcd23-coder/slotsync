-- Preserve the original all-day, all-week, 365-day policy for existing versions.
ALTER TABLE policy_versions
    ADD COLUMN horizon_days integer NOT NULL DEFAULT 365 CHECK (horizon_days BETWEEN 1 AND 365),
    ADD COLUMN opening_days_mask integer NOT NULL DEFAULT 127 CHECK (opening_days_mask BETWEEN 1 AND 127),
    ADD COLUMN opens_at_minute integer NOT NULL DEFAULT 0 CHECK (opens_at_minute BETWEEN 0 AND 1439),
    ADD COLUMN closes_at_minute integer NOT NULL DEFAULT 1440 CHECK (closes_at_minute BETWEEN 1 AND 1440),
    ADD CONSTRAINT ordered_opening_hours CHECK (opens_at_minute < closes_at_minute);
