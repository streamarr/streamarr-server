package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.dto.AccountAdministration;
import java.util.List;

public record RenameAccountPayload(
    AccountAdministration account, List<RenameAccountError> userErrors) {}
