package com.streamarr.server.graphql.mutation.lifecycle;

public record RestrictedFirstAccountError(String message) implements TransferAccountError {}
