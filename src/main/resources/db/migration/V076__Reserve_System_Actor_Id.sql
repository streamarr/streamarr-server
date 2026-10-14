-- The System actor is the reserved identity recorded for work that no user requested. It is not an
-- account, so no account may take its id.
ALTER TABLE user_account
    ADD CONSTRAINT chk_user_account_id_not_system_actor
        CHECK (id <> 'b634c755-3c6a-4ff1-a0a7-3e812d0ed1b9'::uuid);
