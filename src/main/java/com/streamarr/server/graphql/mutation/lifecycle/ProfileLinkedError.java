package com.streamarr.server.graphql.mutation.lifecycle;

public record ProfileLinkedError(String message)
    implements TransferProfileError, ForceDeleteProfileError {}
