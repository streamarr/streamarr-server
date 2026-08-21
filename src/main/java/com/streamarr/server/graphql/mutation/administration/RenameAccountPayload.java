package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.dto.AccountAdministration;
import java.util.List;
import java.util.Optional;

public record RenameAccountPayload(
    Optional<AccountAdministration> account, List<RenameAccountError> userErrors) {}
