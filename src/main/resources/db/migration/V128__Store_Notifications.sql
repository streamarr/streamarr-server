-- Notifications (ADR 0063). Each recipient Account keeps its own copy of what it was told about a
-- subject, made in the transaction that changed the subject. A Notification is a request, which
-- waits for a decision on its subject and reads how it resolved from the subject, never from this
-- table, or news, which stays unread until its recipient marks it read. The kind fixes the form.
-- The first build's subject is one Profile share; created_on is when the first event made the
-- Notification, and latest_event_at moves with each later event of its kind.
--
-- A Notification stores one name only: the Profile's name at its latest event, for a reader who
-- may no longer see the Profile. It stores no picture, no kind, no other name and never a code.
-- It is deleted with its recipient and with its share, and so with the share's Profile or
-- Household; deleting the Account that made the offer deletes nothing.
--
-- The recipient is not a foreign key. A share write tells Accounts that other operations lock FOR
-- UPDATE, such as an Account transfer, and a foreign key's check would make the write wait on each
-- recipient's row. A trigger deletes an Account's Notifications instead. A Notification made while
-- its recipient was being deleted outlives it unseen, since every read is by its recipient, until
-- the retention sweep deletes it.

CREATE TYPE notification_form AS ENUM ('REQUEST', 'NEWS');

CREATE TYPE notification_kind AS ENUM (
    'PROFILE_SHARE_OFFERED',
    'PROFILE_SHARE_ANSWERED',
    'PROFILE_SHARE_ENDED');

CREATE TYPE profile_share_answer AS ENUM ('ACCEPTED', 'REJECTED');

CREATE TYPE profile_share_ended_by AS ENUM ('OFFERING_SIDE', 'HOUSEHOLD_SIDE', 'SERVER');

CREATE TABLE notification
(
    id                   UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by           UUID,
    last_modified_on     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by     UUID,
    recipient_account_id UUID                     NOT NULL,
    kind                 notification_kind        NOT NULL,
    form                 notification_form        NOT NULL,
    share_id             UUID                     NOT NULL,
    profile_name         TEXT                     NOT NULL,
    share_answer         profile_share_answer,
    share_ended_by       profile_share_ended_by,
    latest_event_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    read_at              TIMESTAMP WITH TIME ZONE,
    CONSTRAINT notification_pkey PRIMARY KEY (id),
    CONSTRAINT chk_notification_form CHECK ((kind = 'PROFILE_SHARE_OFFERED') = (form = 'REQUEST')),
    CONSTRAINT chk_notification_share_answer
        CHECK ((kind = 'PROFILE_SHARE_ANSWERED') = (share_answer IS NOT NULL)),
    CONSTRAINT chk_notification_share_ended_by
        CHECK ((kind = 'PROFILE_SHARE_ENDED') = (share_ended_by IS NOT NULL)),
    CONSTRAINT fk_notification_share FOREIGN KEY (share_id)
        REFERENCES profile_household_share (id) ON DELETE CASCADE
);

-- One unread Notification per recipient, kind and subject: a later event of the kind updates the
-- unread one in place. Requests never update in place, so the rule covers news alone.
CREATE UNIQUE INDEX uq_notification_unread_news
    ON notification (recipient_account_id, kind, share_id)
    WHERE form = 'NEWS' AND read_at IS NULL;

-- The recipient's list, newest latest event first.
CREATE INDEX idx_notification_recipient_latest_event
    ON notification (recipient_account_id, latest_event_at DESC, id DESC);

CREATE INDEX idx_notification_share ON notification (share_id);

CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON notification
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();

CREATE FUNCTION delete_notifications_of_deleted_accounts()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    DELETE FROM notification n USING deleted_accounts d WHERE n.recipient_account_id = d.id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER delete_notifications_of_deleted_accounts
    AFTER DELETE
    ON user_account
    REFERENCING OLD TABLE AS deleted_accounts
    FOR EACH STATEMENT
EXECUTE FUNCTION delete_notifications_of_deleted_accounts();
