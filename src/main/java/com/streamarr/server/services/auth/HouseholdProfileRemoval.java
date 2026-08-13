package com.streamarr.server.services.auth;

import java.util.UUID;
import lombok.Builder;

@Builder
public record HouseholdProfileRemoval(UUID actingAccountId, UUID shareId) {}
