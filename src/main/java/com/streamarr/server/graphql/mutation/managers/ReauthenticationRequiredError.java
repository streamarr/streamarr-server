package com.streamarr.server.graphql.mutation.managers;

public record ReauthenticationRequiredError(String message)
    implements AdministrativelyGrantProfileManagerError,
        AdministrativelyRemoveProfileManagerError {}
