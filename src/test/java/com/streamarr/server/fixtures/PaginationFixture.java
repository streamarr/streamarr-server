package com.streamarr.server.fixtures;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import java.util.Optional;

public final class PaginationFixture {

  private PaginationFixture() {}

  public static MediaPaginationOptions buildForwardOptions(int limit, MediaFilter filter) {
    return MediaPaginationOptions.builder()
        .paginationOptions(
            PaginationOptions.builder()
                .cursor(Optional.empty())
                .paginationDirection(PaginationDirection.FORWARD)
                .limit(limit)
                .build())
        .mediaFilter(filter)
        .build();
  }

  public static <T extends BaseAuditableEntity<?>> MediaPaginationOptions buildForwardContinuation(
      int limit, MediaFilter filter, PageItem<T> lastItem) {
    return MediaPaginationOptions.builder()
        .cursorId(lastItem.item().getId())
        .paginationOptions(
            PaginationOptions.builder()
                .cursor(Optional.of("continuation"))
                .paginationDirection(PaginationDirection.FORWARD)
                .limit(limit)
                .build())
        .mediaFilter(filter.toBuilder().previousSortFieldValue(lastItem.sortValue()).build())
        .build();
  }

  public static <T extends BaseAuditableEntity<?>> MediaPaginationOptions buildBackwardContinuation(
      int limit, MediaFilter filter, PageItem<T> firstItem) {
    return MediaPaginationOptions.builder()
        .cursorId(firstItem.item().getId())
        .paginationOptions(
            PaginationOptions.builder()
                .cursor(Optional.of("continuation"))
                .paginationDirection(PaginationDirection.REVERSE)
                .limit(limit)
                .build())
        .mediaFilter(filter.toBuilder().previousSortFieldValue(firstItem.sortValue()).build())
        .build();
  }

  public static <T extends BaseAuditableEntity<?>> MediaPaginationOptions buildCursorOptions(
      int limit, PaginationDirection direction, PageItem<T> item, MediaFilter filter) {
    return MediaPaginationOptions.builder()
        .cursorId(item.item().getId())
        .paginationOptions(
            PaginationOptions.builder()
                .cursor(Optional.of("placeholder"))
                .paginationDirection(direction)
                .limit(limit)
                .build())
        .mediaFilter(filter.toBuilder().previousSortFieldValue(item.sortValue()).build())
        .build();
  }
}
