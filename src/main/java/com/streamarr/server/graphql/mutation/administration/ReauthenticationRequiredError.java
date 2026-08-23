package com.streamarr.server.graphql.mutation.administration;

public record ReauthenticationRequiredError(String message)
    implements GrantServerAdminError, RevokeServerAdminError {}
