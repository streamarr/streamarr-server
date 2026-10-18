package com.streamarr.server.services.pagination;

import java.util.UUID;
import lombok.Builder;
import org.jooq.SortOrder;

@Builder
public record MediaOrder(OrderMediaBy by, SortOrder direction, UUID itemId, Object value) {}
