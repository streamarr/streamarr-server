package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record ReasonRequiredError(String message, List<String> inputPath)
    implements GrantServerAdminError, RevokeServerAdminError, InputMutationError {}
