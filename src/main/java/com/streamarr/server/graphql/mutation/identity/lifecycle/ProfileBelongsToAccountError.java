package com.streamarr.server.graphql.mutation.identity.lifecycle;

public record ProfileBelongsToAccountError(String message)
    implements TransferProfileError, AdministrativelyDeleteProfileError {}
