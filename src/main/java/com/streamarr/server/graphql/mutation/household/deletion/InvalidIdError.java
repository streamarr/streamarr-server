package com.streamarr.server.graphql.mutation.household.deletion;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record InvalidIdError(String message, List<String> inputPath)
    implements DeleteEmptyHouseholdError,
        TransferLastAccountAndDeleteHouseholdError,
        DeleteLastAccountAndHouseholdError,
        DeleteLastAccountAndHouseholdPreservingPersonalProfileError,
        InputMutationError {}
