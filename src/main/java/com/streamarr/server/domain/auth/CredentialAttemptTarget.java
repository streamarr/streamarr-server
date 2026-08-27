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

  public CredentialAttemptTarget {
    var shape = Shape.of(kind);
    shape.account().check(kind, accountId, "accountId");
    shape.profile().check(kind, profileId, "profileId");
    shape.credential().check(kind, credentialId, "credentialId");
  }

  public boolean isResolved() {
    return accountId != null || profileId != null || credentialId != null;
  }

  private enum Presence {
    REQUIRED,
    OPTIONAL,
    ABSENT;

    void check(CredentialKind kind, UUID value, String name) {
      if (this == REQUIRED && value == null) {
        throw new IllegalArgumentException(kind + " target requires " + name);
      }

      if (this == ABSENT && value != null) {
        throw new IllegalArgumentException(kind + " target must not carry " + name);
      }
    }
  }

  /** Which identifiers a kind resolves by; the approver's Account keys device pairing codes. */
  private record Shape(Presence account, Presence profile, Presence credential) {

    static Shape of(CredentialKind kind) {
      return switch (kind) {
        case ACCOUNT_LOGIN -> new Shape(Presence.OPTIONAL, Presence.ABSENT, Presence.ABSENT);
        case ACCOUNT_PASSWORD_VERIFICATION, DEVICE_PAIRING_CODE ->
            new Shape(Presence.REQUIRED, Presence.ABSENT, Presence.ABSENT);
        case PROFILE_PIN -> new Shape(Presence.REQUIRED, Presence.REQUIRED, Presence.ABSENT);
        case ACCOUNT_INVITATION_CODE, PASSWORD_RESET_CODE, PROFILE_MANAGER_INVITATION_CODE ->
            new Shape(Presence.ABSENT, Presence.ABSENT, Presence.OPTIONAL);
      };
    }
  }
}
