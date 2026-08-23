package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface RenameAccountError extends MutationError
    permits AccountNotFoundError, DisplayNameRequiredError, InvalidIdError {}
