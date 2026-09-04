package com.streamarr.server.graphql.mutation.identity.lifecycle;

public record RestrictedFirstAccountError(String message) implements TransferAccountError {}
