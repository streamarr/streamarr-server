package com.streamarr.server.graphql.mutation.sharing;

public record ShareNameConflictError(String message) implements AcceptProfileShareError {}
