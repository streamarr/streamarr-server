package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface GrantServerAdminError extends MutationError
    permits AccountNotFoundError,
        InvalidIdError,
        ReauthenticationRequiredError,
        ReasonRequiredError,
        RestrictedAccountCannotAdministerError {}
