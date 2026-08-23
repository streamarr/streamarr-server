package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface RevokeHouseholdAdminError extends MutationError
    permits AccountNotFoundError, InvalidIdError, LastHouseholdAdminError {}
