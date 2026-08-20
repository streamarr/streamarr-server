package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.dto.AccountAdministration;
import java.util.List;

public record TransferAccountPayload(
    AccountAdministration account, List<TransferAccountError> userErrors) {}
