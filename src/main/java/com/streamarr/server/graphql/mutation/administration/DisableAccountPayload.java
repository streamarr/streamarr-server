package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.dto.AccountAdministration;
import java.util.List;

public record DisableAccountPayload(
    AccountAdministration account, List<DisableAccountError> userErrors) {}
