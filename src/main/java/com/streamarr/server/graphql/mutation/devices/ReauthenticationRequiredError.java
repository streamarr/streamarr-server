package com.streamarr.server.graphql.mutation.devices;

public record ReauthenticationRequiredError(String message) implements BlockEsnServerWideError {}
