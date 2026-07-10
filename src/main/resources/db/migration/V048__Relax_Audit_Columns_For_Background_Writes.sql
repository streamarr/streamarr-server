-- The security auditor is fail-soft: background writers (scan pipeline, listeners) have no
-- request identity and audit as NULL. Legacy tables predate that design; every table written
-- outside a request loses the NOT NULL on created_by.
ALTER TABLE library ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE base_collectable ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE company ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE person ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE rating ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE review ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE media_file ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE external_identifier ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE image ALTER COLUMN created_by DROP NOT NULL;
