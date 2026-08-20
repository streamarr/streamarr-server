package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.dto.EsnBlockView;
import java.util.List;

public record BlockEsnPayload(EsnBlockView block, List<BlockEsnError> userErrors) {}
