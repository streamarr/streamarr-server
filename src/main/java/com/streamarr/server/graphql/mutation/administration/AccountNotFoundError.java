package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record AccountNotFoundError(String message, List<String> inputPath)
    implements DisableAccountError,
        EnableAccountError,
        GrantHouseholdAdminError,
        GrantServerAdminError,
        RenameAccountError,
        RevokeHouseholdAdminError,
        RevokeServerAdminError,
        InputMutationError {}
