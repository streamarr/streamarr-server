package com.streamarr.server.graphql.mutation.credentials;

public record ReauthenticationRequiredError(String message) implements IssuePasswordResetError {}
