package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.dto.AccountAdministration;
import java.util.List;
import java.util.Optional;

public record GrantServerAdminPayload(
    Optional<AccountAdministration> account, List<GrantServerAdminError> userErrors) {}
