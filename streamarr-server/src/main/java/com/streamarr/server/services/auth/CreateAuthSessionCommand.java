package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record CreateAuthSessionCommand(
    UUID accountId, String deviceName, UUID activeHouseholdId, UUID activeProfileId) {}
