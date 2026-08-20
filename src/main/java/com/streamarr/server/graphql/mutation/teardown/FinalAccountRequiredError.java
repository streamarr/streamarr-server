package com.streamarr.server.graphql.mutation.teardown;

public record FinalAccountRequiredError(String message) implements TearDownHouseholdError {}
