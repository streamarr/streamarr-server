package com.streamarr.server.services.pagination;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class MediaFilterComparator {

  private MediaFilterComparator() {}

  public static Optional<String> findMismatch(MediaFilter cursorFilter, MediaFilter currentFilter) {
    return checkField("sortBy", MediaFilter::getSortBy, cursorFilter, currentFilter)
        .or(
            () ->
                checkField(
                    "sortDirection", MediaFilter::getSortDirection, cursorFilter, currentFilter))
        .or(() -> checkField("libraryId", MediaFilter::getLibraryId, cursorFilter, currentFilter))
        .or(() -> checkField("profileId", MediaFilter::getProfileId, cursorFilter, currentFilter))
        .or(() -> checkStartLetter(cursorFilter, currentFilter))
        .or(() -> checkField("genreIds", MediaFilter::getGenreIds, cursorFilter, currentFilter))
        .or(() -> checkField("years", MediaFilter::getYears, cursorFilter, currentFilter))
        .or(
            () ->
                checkField(
                    "contentRatings", MediaFilter::getContentRatings, cursorFilter, currentFilter))
        .or(() -> checkField("studioIds", MediaFilter::getStudioIds, cursorFilter, currentFilter))
        .or(
            () ->
                checkField("directorIds", MediaFilter::getDirectorIds, cursorFilter, currentFilter))
        .or(
            () ->
                checkField(
                    "castMemberIds", MediaFilter::getCastMemberIds, cursorFilter, currentFilter))
        .or(() -> checkField("unmatched", MediaFilter::getUnmatched, cursorFilter, currentFilter))
        .or(
            () ->
                checkField(
                    "watchStatus", MediaFilter::getWatchStatus, cursorFilter, currentFilter));
  }

  // Under TITLE sort, startLetter is a seek anchor consumed by the first page — cursors minted
  // from a letter jump stay valid when later pages omit the letter. For every other sort it is
  // an equality restriction and remains part of the cursor's filter identity.
  private static Optional<String> checkStartLetter(
      MediaFilter cursorFilter, MediaFilter currentFilter) {
    if (cursorFilter.getSortBy() == OrderMediaBy.TITLE) {
      return Optional.empty();
    }
    return checkField("startLetter", MediaFilter::getStartLetter, cursorFilter, currentFilter);
  }

  private static Optional<String> checkField(
      String name, Function<MediaFilter, Object> getter, MediaFilter cursor, MediaFilter current) {
    var cursorValue = getter.apply(cursor);
    var currentValue = getter.apply(current);

    if (Objects.equals(cursorValue, currentValue)) {
      return Optional.empty();
    }

    return Optional.of(name + ": was '" + cursorValue + "' but is now '" + currentValue + "'");
  }
}
