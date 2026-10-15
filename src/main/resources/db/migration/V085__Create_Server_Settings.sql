CREATE TABLE server_settings
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    -- Always TRUE; its unique constraint keeps the table to one row.
    singleton        BOOLEAN                  NOT NULL DEFAULT TRUE,
    encoder_preset   TEXT                     NOT NULL DEFAULT 'AUTOMATIC',
    CONSTRAINT server_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_server_settings_singleton CHECK (singleton),
    CONSTRAINT uq_server_settings_singleton UNIQUE (singleton),
    CONSTRAINT chk_server_settings_encoder_preset CHECK (encoder_preset IN (
        'AUTOMATIC',
        'ULTRAFAST',
        'SUPERFAST',
        'VERYFAST',
        'FASTER',
        'FAST',
        'MEDIUM',
        'SLOW',
        'SLOWER',
        'VERYSLOW'
    ))
);

CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON server_settings
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();

-- The System actor creates the one row.
SELECT set_config('streamarr.actor_id', 'b634c755-3c6a-4ff1-a0a7-3e812d0ed1b9', true);

INSERT INTO server_settings DEFAULT VALUES;
