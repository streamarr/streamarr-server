package com.streamarr.server.graphql.mutation.lifecycle;

import java.util.List;

public record DeleteMyAccountPayload(String accountId, List<DeleteMyAccountError> userErrors) {}
