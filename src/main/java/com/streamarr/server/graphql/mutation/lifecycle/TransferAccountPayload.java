package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.dto.AccountAdministration;
import java.util.List;
import java.util.Optional;

public record TransferAccountPayload(
    Optional<AccountAdministration> account, List<TransferAccountError> userErrors) {}
