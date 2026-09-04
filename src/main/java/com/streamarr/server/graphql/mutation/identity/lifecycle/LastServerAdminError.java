package com.streamarr.server.graphql.mutation.identity.lifecycle;

public record LastServerAdminError(String message)
    implements AdministrativelyDeleteAccountError, DeleteMyAccountError {}
