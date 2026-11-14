package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.ProfileManagersOnlyMessages;
import com.streamarr.server.services.identity.ProfileRejections;
import lombok.NonNull;

/**
 * Only the Profile's managers may invite another, and the caller, who may still read the Profile,
 * is not one of them; reason says what it is to the Profile now. Each union's members share its
 * package, and DGS resolves the schema type by the record name.
 */
public record ProfileManagersOnlyError(String message, ProfileRejections.ManagersOnlyReason reason)
    implements InviteProfileManagerError {

  static ProfileManagersOnlyError of(@NonNull ProfileRejections.ManagersOnly refusal) {
    return new ProfileManagersOnlyError(
        ProfileManagersOnlyMessages.messageFor(refusal.reason()), refusal.reason());
  }
}
