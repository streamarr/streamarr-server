-- Notifications (ADR 0063). A Notification keeps what one recipient was told when something
-- happened to a subject, made inside the transaction of the event that told it. Its kind fixes its
-- form: a request waits for a decision on its subject and stores how it resolved, which only the
-- subject's events write; news stays unread until its recipient marks it read. created_on is when
-- the first event made it, and latest_event_at moves with each later event of its kind.
--
-- The recipient and the subject are references, a type and an id, and neither is a foreign key:
-- the module learns of a deleted subject or recipient from events, never from the database. The
-- snapshot is the kind's record of what the recipient was told, such as the Profile's name; nothing
-- selects, orders or counts by what it holds, so a new kind needs no column and no migration.

CREATE TYPE notification_form AS ENUM ('REQUEST', 'NEWS');

CREATE TYPE request_resolution AS ENUM ('DECIDED', 'WITHDRAWN', 'EXPIRED');

CREATE TABLE notification
(
    id               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    created_on       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by       UUID,
    last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_modified_by UUID,
    recipient_type   TEXT                     NOT NULL,
    recipient_id     UUID                     NOT NULL,
    kind             TEXT                     NOT NULL,
    form             notification_form        NOT NULL,
    subject_type     TEXT                     NOT NULL,
    subject_id       UUID                     NOT NULL,
    snapshot         JSONB                    NOT NULL,
    latest_event_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    read_at          TIMESTAMP WITH TIME ZONE,
    -- A request's state: null while no event has resolved it, and its subject's expiry, from which
    -- it reads as expired before any event says so.
    resolution       request_resolution,
    resolved_at      TIMESTAMP WITH TIME ZONE,
    expires_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT notification_pkey PRIMARY KEY (id),
    CONSTRAINT chk_notification_news_has_no_request_state
        CHECK (form = 'REQUEST'
            OR (resolution IS NULL AND resolved_at IS NULL AND expires_at IS NULL)),
    CONSTRAINT chk_notification_resolution_and_time_together
        CHECK ((resolution IS NULL) = (resolved_at IS NULL))
);

-- One unread news of a kind about a subject for each recipient: a later event of the kind updates
-- it in place, and two events at the same moment update one.
CREATE UNIQUE INDEX uq_notification_unread_news
    ON notification (recipient_type, recipient_id, kind, subject_type, subject_id)
    WHERE form = 'NEWS' AND read_at IS NULL;

-- One request of a kind about a subject for each recipient: a request never updates in place, and
-- a tell published twice makes no second request.
CREATE UNIQUE INDEX uq_notification_request
    ON notification (recipient_type, recipient_id, kind, subject_type, subject_id)
    WHERE form = 'REQUEST';

-- The recipient's list, newest latest event first.
CREATE INDEX idx_notification_recipient_latest_event
    ON notification (recipient_type, recipient_id, latest_event_at DESC, id DESC);

-- The recipient's unread count.
CREATE INDEX idx_notification_recipient_unread
    ON notification (recipient_type, recipient_id)
    WHERE read_at IS NULL AND resolution IS NULL;

-- Resolutions and dismissals by subject.
CREATE INDEX idx_notification_subject ON notification (subject_type, subject_id);

-- Retention.
CREATE INDEX idx_notification_latest_event ON notification (latest_event_at);

CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON notification
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();

-- The guarded Notifications, which the reads use until they move to the table above.
--
-- Each recipient Account keeps its own copy of what it was told about a subject, made in the
-- transaction that changed the subject. A Notification is a request, which
-- waits for a decision on its subject and reads how it resolved from the subject, never from this
-- table, or news, which stays unread until its recipient marks it read. The kind fixes the form.
-- The first build's subject is one Profile share; created_on is when the first event made the
-- Notification, and latest_event_at moves with each later event of its kind.
--
-- A Notification stores one name only: the Profile's name at its latest event, for a reader who
-- may no longer see the Profile. It stores no picture, no kind, no other name and never a code.
-- It also stores the side of the share it was addressed to, which its guard reads: the side the
-- kind tells, set when it is written and never changed, an update in place included.
-- It is deleted with its recipient and with its share, and so with the share's Profile or
-- Household; deleting the Account that made the offer deletes nothing.
--
-- The recipient is not a foreign key. A share write tells Accounts that other operations lock FOR
-- UPDATE, such as an Account transfer, and a foreign key's check would make the write wait on each
-- recipient's row. A trigger deletes an Account's Notifications instead. A Notification made while
-- its recipient was being deleted outlives it unseen, since every read is by its recipient, until
-- the retention sweep deletes it.

CREATE TYPE notification_kind AS ENUM (
    'PROFILE_SHARE_OFFERED',
    'PROFILE_SHARE_ANSWERED',
    'PROFILE_SHARE_ENDED');

CREATE TYPE profile_share_answer AS ENUM ('ACCEPTED', 'REJECTED');

CREATE TYPE profile_share_ended_by AS ENUM ('OFFERING_SIDE', 'HOUSEHOLD_SIDE', 'SERVER');

CREATE TYPE profile_share_side AS ENUM ('OFFERING_SIDE', 'HOUSEHOLD_SIDE');

CREATE TABLE share_notification
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
    addressed_to         profile_share_side       NOT NULL,
    CONSTRAINT share_notification_pkey PRIMARY KEY (id),
    CONSTRAINT chk_share_notification_form
        CHECK ((kind = 'PROFILE_SHARE_OFFERED') = (form = 'REQUEST')),
    CONSTRAINT chk_share_notification_share_answer
        CHECK ((kind = 'PROFILE_SHARE_ANSWERED') = (share_answer IS NOT NULL)),
    CONSTRAINT chk_share_notification_share_ended_by
        CHECK ((kind = 'PROFILE_SHARE_ENDED') = (share_ended_by IS NOT NULL)),
    -- Offer waiting tells the Household's side and Offer answered the offering side; Share ended
    -- tells either.
    CONSTRAINT chk_share_notification_addressed_to
        CHECK ((kind <> 'PROFILE_SHARE_OFFERED' OR addressed_to = 'HOUSEHOLD_SIDE')
            AND (kind <> 'PROFILE_SHARE_ANSWERED' OR addressed_to = 'OFFERING_SIDE')),
    CONSTRAINT fk_share_notification_share FOREIGN KEY (share_id)
        REFERENCES profile_household_share (id) ON DELETE CASCADE
);

-- One unread Notification per recipient, kind and subject: a later event of the kind updates the
-- unread one in place. Requests never update in place, so the rule covers news alone.
CREATE UNIQUE INDEX uq_share_notification_unread_news
    ON share_notification (recipient_account_id, kind, share_id)
    WHERE form = 'NEWS' AND read_at IS NULL;

-- The recipient's list, newest latest event first.
CREATE INDEX idx_share_notification_recipient_latest_event
    ON share_notification (recipient_account_id, latest_event_at DESC, id DESC);

CREATE INDEX idx_share_notification_share ON share_notification (share_id);

CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON share_notification
    FOR EACH ROW EXECUTE FUNCTION fill_audit_columns();

CREATE FUNCTION delete_notifications_of_deleted_accounts()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    DELETE FROM share_notification n USING deleted_accounts d WHERE n.recipient_account_id = d.id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER delete_notifications_of_deleted_accounts
    AFTER DELETE
    ON user_account
    REFERENCING OLD TABLE AS deleted_accounts
    FOR EACH STATEMENT
EXECUTE FUNCTION delete_notifications_of_deleted_accounts();
