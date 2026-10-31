package com.streamarr.server.services.auth;

import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record CreateAuthSessionCommand(
    @NonNull UUID accountId,
    String deviceName,
    UUID contextHouseholdId,
    UUID selectedProfileId,
    @NonNull Optional<UUID> registrationId) {

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class CreateAuthSessionCommandBuilder {
    private Optional<UUID> registrationId = Optional.empty();
  }
}
