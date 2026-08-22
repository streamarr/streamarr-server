package com.streamarr.server.graphql.mutation.sharing;

public record NoEligibleAdminError(String message) implements AcceptProfileShareError {}
