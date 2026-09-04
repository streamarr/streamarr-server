package com.streamarr.server.graphql.mutation.identity.lifecycle;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record InvalidIdError(String message, List<String> inputPath)
    implements TransferAccountError,
        AdministrativelyDeleteAccountError,
        TransferProfileError,
        AdministrativelyDeleteProfileError,
        InputMutationError {}
