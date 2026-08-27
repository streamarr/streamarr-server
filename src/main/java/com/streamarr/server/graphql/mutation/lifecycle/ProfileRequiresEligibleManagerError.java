package com.streamarr.server.graphql.mutation.lifecycle;

public record ProfileRequiresEligibleManagerError(String message)
    implements TransferAccountError, DeleteAccountError, DeleteMyAccountError {}
