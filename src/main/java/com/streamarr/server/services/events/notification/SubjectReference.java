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

  /** One Profile. */
  public static final String PROFILE = "PROFILE";

  /** One ProfileManager invitation. */
  public static final String PROFILE_MANAGER_INVITATION = "PROFILE_MANAGER_INVITATION";

  public static SubjectReference profileShare(UUID shareId) {
    return new SubjectReference(PROFILE_SHARE, shareId);
  }

  public static SubjectReference profile(UUID profileId) {
    return new SubjectReference(PROFILE, profileId);
  }

  public static SubjectReference profileManagerInvitation(UUID invitationId) {
    return new SubjectReference(PROFILE_MANAGER_INVITATION, invitationId);
  }
}
