package com.streamarr.server.graphql.mutation.lifecycle;

public record LastHouseholdAccountError(String message)
    implements TransferAccountError, DeleteAccountError, DeleteMyAccountError {}
