package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record CreateAuthSessionCommand(
    @NonNull UUID accountId, String deviceName, UUID contextHouseholdId, UUID selectedProfileId) {}
