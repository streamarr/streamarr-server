package com.streamarr.server.graphql.mutation.sharing;

public record ReauthenticationRequiredError(String message) implements ForceEndProfileShareError {}
