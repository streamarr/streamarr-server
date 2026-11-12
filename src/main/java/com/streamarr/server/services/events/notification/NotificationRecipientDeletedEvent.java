package com.streamarr.server.services.events.notification;

import lombok.NonNull;

/**
 * A recipient is being deleted, so its Notifications go with it (ADR 0063 §Deletion, disabling, and
 * the end of a subject). Its domain publishes it inside the transaction that deletes the recipient.
 * Nothing is announced, because the recipient's sessions end with it.
 */
public record NotificationRecipientDeletedEvent(@NonNull RecipientReference recipient) {}
