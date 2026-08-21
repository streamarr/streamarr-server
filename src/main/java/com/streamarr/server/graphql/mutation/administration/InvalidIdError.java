package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record InvalidIdError(String message, List<String> inputPath)
    implements DisableAccountError,
        EnableAccountError,
        GrantHouseholdAdminError,
        GrantServerAdminError,
        RenameAccountError,
        RenameHouseholdError,
        RevokeHouseholdAdminError,
        RevokeServerAdminError,
        InputMutationError {}
