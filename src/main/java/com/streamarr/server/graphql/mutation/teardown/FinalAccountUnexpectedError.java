package com.streamarr.server.graphql.mutation.teardown;

public record FinalAccountUnexpectedError(String message) implements TearDownHouseholdError {}
