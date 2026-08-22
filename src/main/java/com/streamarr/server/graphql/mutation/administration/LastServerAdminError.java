package com.streamarr.server.graphql.mutation.administration;

public record LastServerAdminError(String message)
    implements DisableAccountError, RevokeServerAdminError {}
