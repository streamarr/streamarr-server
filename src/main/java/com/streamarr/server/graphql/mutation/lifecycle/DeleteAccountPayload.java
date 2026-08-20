package com.streamarr.server.graphql.mutation.lifecycle;

import java.util.List;

public record DeleteAccountPayload(String accountId, List<DeleteAccountError> userErrors) {}
