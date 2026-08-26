package com.streamarr.server.domain.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record CredentialAttemptTarget(
    @NonNull CredentialKind kind,
    UUID accountId,
    UUID profileId,
    UUID credentialId,
    @NonNull String ipAddress) {

  public boolean isResolved() {
    return accountId != null || profileId != null || credentialId != null;
  }
}
