package com.streamarr.server.graphql.mutation.lifecycle;

public record FinalAccountError(String message)
    implements TransferAccountError, DeleteAccountError, DeleteMyAccountError {}
