package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record ProfileNotFoundError(String message, List<String> inputPath)
    implements InviteProfileManagerError,
        RelinquishProfileManagementError,
        RemoveProfileManagerError,
        AdministrativelyGrantProfileManagerError,
        AdministrativelyRemoveProfileManagerError,
        InputMutationError {}
