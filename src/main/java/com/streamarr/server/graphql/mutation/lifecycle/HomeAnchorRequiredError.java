package com.streamarr.server.graphql.mutation.lifecycle;

public record HomeAnchorRequiredError(String message)
    implements TransferAccountError, DeleteAccountError, DeleteMyAccountError {}
