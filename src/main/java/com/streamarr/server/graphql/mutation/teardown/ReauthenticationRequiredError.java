package com.streamarr.server.graphql.mutation.teardown;

public record ReauthenticationRequiredError(String message) implements TearDownHouseholdError {}
