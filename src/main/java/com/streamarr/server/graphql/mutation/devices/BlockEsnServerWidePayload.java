package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.dto.EsnBlockDetails;
import java.util.List;
import java.util.Optional;

public record BlockEsnServerWidePayload(
    Optional<EsnBlockDetails> block, List<BlockEsnServerWideError> userErrors) {}
