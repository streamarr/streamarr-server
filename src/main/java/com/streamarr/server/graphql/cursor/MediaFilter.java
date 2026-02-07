package com.streamarr.server.graphql.cursor;

import java.util.UUID;
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

  @Builder.Default private final OrderMediaBy sortBy = OrderMediaBy.TITLE;
  @Builder.Default private final SortOrder sortDirection = SortOrder.ASC;

  private UUID libraryId;

  // Used for seek pagination
  private Object previousSortFieldValue;
}
