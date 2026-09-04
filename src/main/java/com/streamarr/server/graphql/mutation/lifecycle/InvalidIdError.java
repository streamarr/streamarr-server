package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record InvalidIdError(String message, List<String> inputPath)
    implements TransferAccountError,
        DeleteAccountError,
        TransferProfileError,
        AdministrativelyDeleteProfileError,
        InputMutationError {}
