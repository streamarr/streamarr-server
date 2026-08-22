package com.streamarr.server.graphql.mutation.lifecycle;

public record ReauthenticationRequiredError(String message)
    implements DeleteAccountError, DeleteMyAccountError, ForceDeleteProfileError {}
