package com.streamarr.server.graphql.mutation.managers;

public record ManagementAlreadyRemovedError(String message)
    implements RelinquishProfileManagementError {}
