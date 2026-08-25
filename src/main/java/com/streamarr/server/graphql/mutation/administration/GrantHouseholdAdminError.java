package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface GrantHouseholdAdminError extends MutationError
    permits AccountNotFoundError, InvalidIdError, RestrictedAccountCannotAdministerError {}
