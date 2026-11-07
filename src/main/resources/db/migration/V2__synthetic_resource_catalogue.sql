INSERT INTO resource_types (code, display_name) VALUES
    ('ROOM', 'Room'), ('EQUIPMENT', 'Equipment');

-- Entirely synthetic campus fixtures. No people, credentials or real facilities.
INSERT INTO resources (id, code, display_name, type_code, location, capacity) VALUES
    ('10000000-0000-0000-0000-000000000001', 'ROOM-ATLAS', 'Atlas meeting room', 'ROOM', 'Demo campus / North wing', 8),
    ('10000000-0000-0000-0000-000000000002', 'ROOM-BIRCH', 'Birch seminar room', 'ROOM', 'Demo campus / South wing', 24),
    ('10000000-0000-0000-0000-000000000003', 'KIT-PROJECTOR', 'Portable projector kit', 'EQUIPMENT', 'Demo campus / Equipment desk', 1);
