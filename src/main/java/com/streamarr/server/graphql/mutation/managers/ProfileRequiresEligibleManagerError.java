package com.streamarr.server.graphql.mutation.managers;

public record ProfileRequiresEligibleManagerError(String message)
    implements RelinquishProfileManagementError,
        RemoveProfileManagerError,
        AdministrativelyRemoveProfileManagerError {}
