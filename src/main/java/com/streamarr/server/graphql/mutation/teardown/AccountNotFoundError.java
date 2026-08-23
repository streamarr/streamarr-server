package com.streamarr.server.graphql.mutation.teardown;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record AccountNotFoundError(String message, List<String> inputPath)
    implements TearDownHouseholdError, InputMutationError {}
