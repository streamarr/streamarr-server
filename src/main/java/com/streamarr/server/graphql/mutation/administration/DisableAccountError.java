package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface DisableAccountError extends MutationError
    permits AccountNotFoundError, InvalidIdError, LastServerAdminError {}
