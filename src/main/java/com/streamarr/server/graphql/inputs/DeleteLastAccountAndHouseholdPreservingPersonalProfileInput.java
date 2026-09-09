package com.streamarr.server.graphql.inputs;

public record DeleteLastAccountAndHouseholdPreservingPersonalProfileInput(
    String householdId,
    String destinationHouseholdId,
    String replacementManagerAccountId,
    String reason) {}
