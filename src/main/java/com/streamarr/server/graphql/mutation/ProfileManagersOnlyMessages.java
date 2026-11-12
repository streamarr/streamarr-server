package com.streamarr.server.graphql.mutation;

import com.streamarr.server.services.identity.ProfileRejections;
import lombok.NonNull;

/** The words of ProfileManagersOnlyError, the same in every union that answers with it. */
public final class ProfileManagersOnlyMessages {

  private ProfileManagersOnlyMessages() {}

  public static String messageFor(@NonNull ProfileRejections.ManagersOnlyReason reason) {
    return switch (reason) {
      case NOT_MANAGER ->
          "Only the Profile's managers may do this; supervising it does not cover this.";
      case NOT_RESTRICTED -> "The Profile is not restricted, so only its managers may do this.";
    };
  }
}
