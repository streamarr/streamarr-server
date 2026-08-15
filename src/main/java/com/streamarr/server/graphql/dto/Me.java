package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.HouseholdRole;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record Me(
    UUID accountId,
    String email,
    String displayName,
    String role,
    UUID homeHouseholdId,
    HouseholdRole householdRole,
    String scope,
    List<SelectableProfile> profiles) {}
