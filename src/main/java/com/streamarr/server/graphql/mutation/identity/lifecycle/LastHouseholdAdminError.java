package com.streamarr.server.graphql.mutation.identity.lifecycle;

public record LastHouseholdAdminError(String message)
    implements TransferAccountError, AdministrativelyDeleteAccountError, DeleteMyAccountError {}
