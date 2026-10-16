-- libx264 at ultrafast writes Constrained Baseline, so its codec string never matches the job's
-- (ADR 0044). Superfast is the fastest preset left on the scale.
SELECT set_config('streamarr.actor_id', 'b634c755-3c6a-4ff1-a0a7-3e812d0ed1b9', true);

UPDATE server_settings
SET encoder_preset = 'SUPERFAST'
WHERE encoder_preset = 'ULTRAFAST';

ALTER TABLE server_settings
    DROP CONSTRAINT chk_server_settings_encoder_preset;

ALTER TABLE server_settings
    ADD CONSTRAINT chk_server_settings_encoder_preset CHECK (encoder_preset IN (
        'AUTOMATIC',
        'SUPERFAST',
        'VERYFAST',
        'FASTER',
        'FAST',
        'MEDIUM',
        'SLOW',
        'SLOWER',
        'VERYSLOW'
    ));
