package com.streamarr.server.graphql.mutation.identity.lifecycle;

public record ProfileRequiresEligibleManagerError(String message)
    implements TransferAccountError, AdministrativelyDeleteAccountError, DeleteMyAccountError {}
