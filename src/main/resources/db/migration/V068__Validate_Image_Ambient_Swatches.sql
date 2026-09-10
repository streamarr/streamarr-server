-- Validate after V067 commits so the table scan does not retain the column addition's
-- ACCESS EXCLUSIVE lock.
ALTER TABLE image
    VALIDATE CONSTRAINT chk_image_ambient_swatches_require_primary;
