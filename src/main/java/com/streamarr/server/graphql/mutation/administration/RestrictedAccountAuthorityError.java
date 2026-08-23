package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record RestrictedAccountAuthorityError(String message, List<String> inputPath)
    implements GrantHouseholdAdminError, GrantServerAdminError, InputMutationError {}
