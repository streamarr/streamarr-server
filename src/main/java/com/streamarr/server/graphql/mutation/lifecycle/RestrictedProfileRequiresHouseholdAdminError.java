package com.streamarr.server.graphql.mutation.lifecycle;

public record RestrictedProfileRequiresHouseholdAdminError(String message)
    implements TransferAccountError,
        DeleteAccountError,
        DeleteMyAccountError,
        TransferProfileError {}
