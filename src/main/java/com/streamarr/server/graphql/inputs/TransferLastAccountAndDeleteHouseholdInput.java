package com.streamarr.server.graphql.inputs;

public record TransferLastAccountAndDeleteHouseholdInput(
    String householdId, String destinationHouseholdId, String reason) {}
