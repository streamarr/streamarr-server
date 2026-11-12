package com.streamarr.server.services.events.notification;

import java.util.UUID;
import lombok.NonNull;

/**
 * Who a Notification tells, by type and id (ADR 0063 §What a Notification is, and is not). Every
 * recipient today is an Account. Nothing makes it a foreign key: the module learns of a deleted
 * recipient from an event, never from the database.
 */
public record RecipientReference(@NonNull String type, @NonNull UUID id) {

  public static final String ACCOUNT = "ACCOUNT";

  public static RecipientReference account(UUID accountId) {
    return new RecipientReference(ACCOUNT, accountId);
  }
}
