package com.streamarr.server.graphql.mutation.lifecycle;

public record LastServerAdminError(String message)
    implements DeleteAccountError, DeleteMyAccountError {}
