package com.streamarr.server.services.pagination;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.BaseCollectable;
import java.util.Optional;
import java.util.function.Function;

/**
 * Converts a cursor-less TITLE-sort request carrying a startLetter into an ordinary keyset-cursor
 * fetch anchored at the row immediately above the letter (the predecessor in the requested
 * ordering). The regular cursor machinery then strips the predecessor row, reports whether a
 * previous page exists, and mints cursors that page backward across the letter boundary.
 */
public final class LetterJumpResolver {

  private LetterJumpResolver() {}

  public static <T extends BaseCollectable<T>> MediaPaginationOptions resolve(
      MediaPaginationOptions options, Function<MediaFilter, Optional<T>> predecessorFinder) {

    if (!isLetterJump(options)) {
      return options;
    }

    if (isDescendingHashJump(options) && !hasTitlesBelowA(options, predecessorFinder)) {
      return landAtTop(options);
    }

    return predecessorFinder
        .apply(options.getMediaFilter())
        .map(predecessor -> anchorAtPredecessor(options, predecessor))
        .orElse(options);
  }

  private static boolean isLetterJump(MediaPaginationOptions options) {
    return options.getCursorId().isEmpty()
        && options.getMediaFilter().getStartLetter() != null
        && options.getMediaFilter().getSortBy() == OrderMediaBy.TITLE;
  }

  private static boolean isDescendingHashJump(MediaPaginationOptions options) {
    var filter = options.getMediaFilter();
    return filter.getStartLetter() == AlphabetLetter.HASH
        && filter.getSortDirection() == SortDirection.DESC;
  }

  // Descending HASH lands on the titles below "a". Without any, every HASH title sorts above "z",
  // at the top of the ordering. An ascending jump to A has a predecessor exactly when a title
  // sorts below "a".
  private static <T extends BaseCollectable<T>> boolean hasTitlesBelowA(
      MediaPaginationOptions options, Function<MediaFilter, Optional<T>> predecessorFinder) {
    var ascendingJumpToA =
        options.getMediaFilter().toBuilder()
            .sortDirection(SortDirection.ASC)
            .startLetter(AlphabetLetter.A)
            .build();
    return predecessorFinder.apply(ascendingJumpToA).isPresent();
  }

  private static MediaPaginationOptions landAtTop(MediaPaginationOptions options) {
    return options.toBuilder()
        .mediaFilter(options.getMediaFilter().toBuilder().startLetter(null).build())
        .build();
  }

  private static MediaPaginationOptions anchorAtPredecessor(
      MediaPaginationOptions options, BaseCollectable<?> predecessor) {
    return options.toBuilder()
        .cursorId(predecessor.getId())
        .mediaFilter(
            options.getMediaFilter().toBuilder()
                .previousSortFieldValue(predecessor.getTitleSort())
                .build())
        .build();
  }
}
