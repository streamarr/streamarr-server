package com.streamarr.server.graphql.inputs;

public record SetProfileContentCeilingInput(String profileId, int maximumAllowedRatingAge) {}
