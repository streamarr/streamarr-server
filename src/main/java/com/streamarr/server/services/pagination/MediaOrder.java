package com.streamarr.server.services.pagination;

import java.util.UUID;
import lombok.Builder;

@Builder
public record MediaOrder(OrderMediaBy by, SortDirection direction, UUID itemId, Object value) {}
