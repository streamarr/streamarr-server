package com.streamarr.server.graphql.mutation.teardown;

public record LastAccountActionNotAllowedError(String message) implements TearDownHouseholdError {}
