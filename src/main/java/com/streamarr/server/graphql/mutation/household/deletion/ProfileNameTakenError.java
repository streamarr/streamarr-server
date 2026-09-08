package com.streamarr.server.graphql.mutation.household.deletion;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record ProfileNameTakenError(String message, List<String> inputPath)
    implements TransferLastAccountAndDeleteHouseholdError,
        DeleteLastAccountAndHouseholdPreservingPersonalProfileError,
        InputMutationError {}
