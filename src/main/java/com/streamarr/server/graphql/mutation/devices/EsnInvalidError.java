package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record EsnInvalidError(String message, List<String> inputPath)
    implements BlockEsnError,
        BlockEsnServerWideError,
        UnblockEsnError,
        UnblockEsnServerWideError,
        InputMutationError {}
