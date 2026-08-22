package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.HouseholdRole;
import java.util.UUID;
import lombok.Builder;

@Builder
public record Me(
    UUID accountId,
    String email,
    String displayName,
    boolean serverAdmin,
    String scope,
    HouseholdSummary household,
    HouseholdRole householdRole,
    HouseholdSummary contextHousehold,
    boolean deviceBound) {}
