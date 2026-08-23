package com.streamarr.server.graphql.mutation.teardown;

public record LastAccountActionRequiredError(String message) implements TearDownHouseholdError {}
