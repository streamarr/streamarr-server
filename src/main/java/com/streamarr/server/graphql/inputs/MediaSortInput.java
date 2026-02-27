package com.streamarr.server.graphql.inputs;

import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.graphql.cursor.SortDirection;

public record MediaSortInput(OrderMediaBy by, SortDirection direction) {}
