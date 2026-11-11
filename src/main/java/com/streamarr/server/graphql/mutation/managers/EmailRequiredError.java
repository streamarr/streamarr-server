package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record EmailRequiredError(String message, List<String> inputPath)
    implements InviteProfileManagerError, InputMutationError {}
