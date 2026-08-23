package com.streamarr.server.graphql.mutation.profile;

import java.util.UUID;

public record ProfilePinRequiredError(String message, UUID householdId)
    implements RemoveProfilePinError {}
