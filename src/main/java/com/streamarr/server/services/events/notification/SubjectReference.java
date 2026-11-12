package com.streamarr.server.services.events.notification;

import java.util.UUID;
import lombok.NonNull;

/**
 * The record a Notification is about, by its type and its id (ADR 0063 §What a Notification is, and
 * is not). It is the Notification's link target, and nothing makes it a foreign key: the module
 * learns of a deleted subject from a dismissal, never from the database.
 */
public record SubjectReference(@NonNull String type, @NonNull UUID id) {

  /** One Profile share, from the pending offer to the ended share. */
  public static final String PROFILE_SHARE = "PROFILE_SHARE";

  public static SubjectReference profileShare(UUID shareId) {
    return new SubjectReference(PROFILE_SHARE, shareId);
  }
}
