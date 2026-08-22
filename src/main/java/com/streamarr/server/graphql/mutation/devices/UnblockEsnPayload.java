package com.streamarr.server.graphql.mutation.devices;

import java.util.List;
import java.util.Optional;

public record UnblockEsnPayload(Optional<String> esn, List<UnblockEsnError> userErrors) {}
