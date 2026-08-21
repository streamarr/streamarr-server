package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.dto.EsnBlockView;
import java.util.List;
import java.util.Optional;

public record BlockEsnPayload(Optional<EsnBlockView> block, List<BlockEsnError> userErrors) {}
