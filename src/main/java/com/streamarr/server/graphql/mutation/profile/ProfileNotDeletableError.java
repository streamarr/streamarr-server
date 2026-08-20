package com.streamarr.server.graphql.mutation.profile;

public record ProfileNotDeletableError(String message) implements DeleteProfileError {}
