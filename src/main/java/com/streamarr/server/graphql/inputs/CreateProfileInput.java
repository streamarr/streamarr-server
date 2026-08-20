package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.auth.ProfileKind;

public record CreateProfileInput(
    String householdId,
    String name,
    ProfileKind kind,
    Integer maximumAllowedRatingAge,
    String localManagerAccountId) {}
