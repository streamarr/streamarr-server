package com.streamarr.server.graphql.mutation.devices;

import java.util.List;
import java.util.Optional;

public record UnblockEsnServerWidePayload(
    Optional<String> esn, List<UnblockEsnServerWideError> userErrors) {}
