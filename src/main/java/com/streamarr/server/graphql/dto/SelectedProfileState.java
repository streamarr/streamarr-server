package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import lombok.NonNull;

/** One media title seen by the selected Profile that the identity names (ADR 0056). */
public record SelectedProfileState(
    @NonNull AuthenticatedIdentity identity,
    @NonNull UUID titleId,
    @NonNull CollectableScope scope) {

  public UUID profileId() {
    return identity.profileId();
  }
}
