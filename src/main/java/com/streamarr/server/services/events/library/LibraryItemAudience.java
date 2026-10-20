package com.streamarr.server.services.events.library;

import java.util.UUID;
import lombok.NonNull;

/**
 * The Profiles whose subscriptions receive an update. A watch-state change concerns only the
 * Profile that watched; every other change concerns every subscribed Profile.
 */
public sealed interface LibraryItemAudience {

  boolean includes(UUID profileId);

  static LibraryItemAudience everyProfile() {
    return new EveryProfile();
  }

  static LibraryItemAudience profile(@NonNull UUID profileId) {
    return new OneProfile(profileId);
  }

  record EveryProfile() implements LibraryItemAudience {
    @Override
    public boolean includes(UUID profileId) {
      return true;
    }
  }

  record OneProfile(@NonNull UUID profileId) implements LibraryItemAudience {
    @Override
    public boolean includes(UUID candidateProfileId) {
      return profileId.equals(candidateProfileId);
    }
  }
}
