package com.streamarr.server.services.pagination;

import java.util.List;
import lombok.NonNull;

/** One page of a list in list order, and whether rows exist on either side of it. */
public record ListPage<T>(
    @NonNull List<Row<T>> rows, boolean hasNextPage, boolean hasPreviousPage) {}
