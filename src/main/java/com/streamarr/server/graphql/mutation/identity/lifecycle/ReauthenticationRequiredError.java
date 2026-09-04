package com.streamarr.server.graphql.mutation.identity.lifecycle;

public record ReauthenticationRequiredError(String message)
    implements AdministrativelyDeleteAccountError,
        DeleteMyAccountError,
        AdministrativelyDeleteProfileError {}
