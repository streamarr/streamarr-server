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

INSERT INTO server_settings DEFAULT VALUES;
