package com.streamarr.server.graphql.inputs;

import com.streamarr.server.graphql.cursor.SortDirection;
import com.streamarr.server.services.pagination.OrderMediaBy;

public record MediaSortInput(OrderMediaBy by, SortDirection direction) {}
