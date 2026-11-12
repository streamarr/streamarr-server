package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.ProfileManagersOnlyMessages;
import com.streamarr.server.services.identity.ProfileRejections;
import lombok.NonNull;

/**
 * Only the Profile's managers may offer it to another Household, and the caller, who may still read
 * the Profile, is not one of them; reason says what it is to the Profile now. Each union's members
 * share its package, and DGS resolves the schema type by the record name.
 */
public record ProfileManagersOnlyError(String message, ProfileRejections.ManagersOnlyReason reason)
    implements OfferProfileShareError {

  static ProfileManagersOnlyError of(@NonNull ProfileRejections.ManagersOnly refusal) {
    return new ProfileManagersOnlyError(
        ProfileManagersOnlyMessages.messageFor(refusal.reason()), refusal.reason());
  }
}
