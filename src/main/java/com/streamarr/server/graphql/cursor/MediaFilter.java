package com.streamarr.server.graphql.cursor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jooq.SortOrder;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MediaFilter {

    @Builder.Default
    private final OrderMoviesBy sortBy = OrderMoviesBy.TITLE;
    @Builder.Default
    private final SortOrder sortDirection = SortOrder.ASC;

    // Used for seek pagination
    private Object previousSortFieldValue;
}
