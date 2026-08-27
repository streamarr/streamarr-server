package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record ProfileNotFoundError(String message, List<String> inputPath)
    implements TransferProfileError, AdministrativelyDeleteProfileError, InputMutationError {}
