package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileShareOffer(UUID actingAccountId, UUID profileId, UUID targetHouseholdId) {}
