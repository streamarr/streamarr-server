package com.streamarr.server.graphql.mutation.identity.lifecycle;

public record RestrictedProfileRequiresHouseholdAdminError(String message)
    implements TransferAccountError,
        AdministrativelyDeleteAccountError,
        DeleteMyAccountError,
        TransferProfileError {}
