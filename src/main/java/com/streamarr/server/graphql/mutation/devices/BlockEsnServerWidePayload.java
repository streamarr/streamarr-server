package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.dto.EsnBlockView;
import java.util.List;
import java.util.Optional;

public record BlockEsnServerWidePayload(
    Optional<EsnBlockView> block, List<BlockEsnServerWideError> userErrors) {}
