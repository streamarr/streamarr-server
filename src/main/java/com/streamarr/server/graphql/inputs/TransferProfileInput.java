package com.streamarr.server.graphql.inputs;

public record TransferProfileInput(
    String profileId, String destinationHouseholdId, String localManagerAccountId, String reason) {}
