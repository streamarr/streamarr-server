package com.streamarr.server.graphql.mutation.teardown;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record DestinationNotFoundError(String message, List<String> inputPath)
    implements TearDownHouseholdError, InputMutationError {}
