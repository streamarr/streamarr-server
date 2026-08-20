package com.streamarr.server.graphql.mutation.lifecycle;

public record NoEligibleAdminError(String message)
    implements TransferAccountError,
        DeleteAccountError,
        DeleteMyAccountError,
        TransferProfileError {}
