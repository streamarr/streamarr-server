-- Notifications (ADR 0063). A Notification keeps what one recipient was told when something
-- happened to a subject, made inside the transaction of the event that told it. Its kind fixes its
-- form: a request waits for a decision on its subject and stores how it resolved, which only the
-- subject's events write; news stays unread until its recipient marks it read. first_event_at is
-- when the first event made it, and latest_event_at moves with each later event of its kind. A
-- Notification is not an audit record, so it keeps no Account that acted.
--
-- The recipient and the subject are references, a type and an id, and neither is a foreign key:
-- the module learns of a deleted subject or recipient from events, never from the database. The
-- snapshot is the kind's record of what the recipient was told, such as the Profile's name; nothing
-- selects, orders or counts by what it holds, so a new kind needs no column and no migration.

CREATE TYPE notification_form AS ENUM ('REQUEST', 'NEWS');

CREATE TYPE request_resolution AS ENUM ('DECIDED', 'WITHDRAWN', 'EXPIRED');

CREATE TABLE notification
(
    id              UUID                     NOT NULL DEFAULT gen_random_uuid(),
    recipient_type  TEXT                     NOT NULL,
    recipient_id    UUID                     NOT NULL,
    kind            TEXT                     NOT NULL,
    form            notification_form        NOT NULL,
    subject_type    TEXT                     NOT NULL,
    subject_id      UUID                     NOT NULL,
    snapshot        JSONB                    NOT NULL,
    first_event_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    latest_event_at TIMESTAMP WITH TIME ZONE NOT NULL,
    read_at         TIMESTAMP WITH TIME ZONE,
    -- A request's state: null while no event has resolved it, and its subject's expiry, from which
    -- it reads as expired before any event says so.
    resolution      request_resolution,
    resolved_at     TIMESTAMP WITH TIME ZONE,
    expires_at      TIMESTAMP WITH TIME ZONE,
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
