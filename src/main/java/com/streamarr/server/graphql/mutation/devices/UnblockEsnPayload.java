package com.streamarr.server.graphql.mutation.devices;

import java.util.List;

public record UnblockEsnPayload(String esn, List<UnblockEsnError> userErrors) {}
