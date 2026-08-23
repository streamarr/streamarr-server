package com.streamarr.server.graphql.mutation.lifecycle;

public record EligibleManagerRequiredError(String message)
    implements TransferAccountError, DeleteAccountError, DeleteMyAccountError {}
