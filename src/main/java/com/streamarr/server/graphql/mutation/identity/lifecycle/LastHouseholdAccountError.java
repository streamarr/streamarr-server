package com.streamarr.server.graphql.mutation.identity.lifecycle;

public record LastHouseholdAccountError(String message)
    implements TransferAccountError, AdministrativelyDeleteAccountError, DeleteMyAccountError {}
