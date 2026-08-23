package com.streamarr.server.graphql.mutation.profile;

import java.util.UUID;

/** The Household is inside the message only when the caller may view it. */
public record WouldLockProfileError(String message, UUID householdId)
    implements RemoveProfilePinError {}
