package com.streamarr.server.graphql.inputs;

import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.SortDirection;

public record MediaSortInput(OrderMediaBy by, SortDirection direction) {}
