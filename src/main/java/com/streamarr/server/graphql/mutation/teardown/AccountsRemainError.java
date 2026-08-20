package com.streamarr.server.graphql.mutation.teardown;

public record AccountsRemainError(String message) implements TearDownHouseholdError {}
