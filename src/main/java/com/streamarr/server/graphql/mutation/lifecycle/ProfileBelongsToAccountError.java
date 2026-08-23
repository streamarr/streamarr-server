package com.streamarr.server.graphql.mutation.lifecycle;

public record ProfileBelongsToAccountError(String message)
    implements TransferProfileError, ForceDeleteProfileError {}
