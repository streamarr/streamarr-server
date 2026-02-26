package com.streamarr.server.graphql.inputs;

import com.streamarr.server.graphql.cursor.OrderMediaBy;
import org.jooq.SortOrder;

public record MediaSortInput(OrderMediaBy by, SortOrder direction) {}
