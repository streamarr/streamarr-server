package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface RenameHouseholdError extends MutationError
    permits HouseholdNotFoundError, HouseholdNameRequiredError, InvalidIdError {}
