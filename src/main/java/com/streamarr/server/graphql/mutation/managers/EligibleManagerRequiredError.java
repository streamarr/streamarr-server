package com.streamarr.server.graphql.mutation.managers;

public record EligibleManagerRequiredError(String message)
    implements RelinquishProfileManagementError,
        RemoveProfileManagerError,
        RemoveProfileManagerOverrideError {}
