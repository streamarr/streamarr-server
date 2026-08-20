package com.streamarr.server.graphql.inputs;

public record RemoveProfileManagerOverrideInput(
    String profileId, String accountId, String reason) {}
