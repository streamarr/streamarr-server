package com.streamarr.server.graphql.mutation.lifecycle;

public record LastHouseholdAdminError(String message)
    implements TransferAccountError, DeleteAccountError, DeleteMyAccountError {}
