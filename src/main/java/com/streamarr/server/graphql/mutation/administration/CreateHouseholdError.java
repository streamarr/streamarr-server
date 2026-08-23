package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface CreateHouseholdError extends MutationError
    permits HouseholdNameRequiredError {}
