package com.streamarr.server.graphql.inputs;

public record SetProfileMaximumAllowedRatingAgeInput(
    String profileId, int maximumAllowedRatingAge) {}
