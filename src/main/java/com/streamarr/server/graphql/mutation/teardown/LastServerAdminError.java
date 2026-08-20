package com.streamarr.server.graphql.mutation.teardown;

public record LastServerAdminError(String message) implements TearDownHouseholdError {}
