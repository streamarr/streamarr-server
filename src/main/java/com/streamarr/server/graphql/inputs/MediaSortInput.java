package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.pagination.OrderMediaBy;
import com.streamarr.server.domain.pagination.SortDirection;

public record MediaSortInput(OrderMediaBy by, SortDirection direction) {}
