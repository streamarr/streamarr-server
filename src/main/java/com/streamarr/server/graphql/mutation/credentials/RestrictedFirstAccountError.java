package com.streamarr.server.graphql.mutation.credentials;

public record RestrictedFirstAccountError(String message) implements IssueAccountInvitationError {}
