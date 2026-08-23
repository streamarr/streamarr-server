package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface EnableAccountError extends MutationError
    permits AccountNotFoundError, InvalidIdError {}
