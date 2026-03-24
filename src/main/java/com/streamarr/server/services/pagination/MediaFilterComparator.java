package com.streamarr.server.services.pagination;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class MediaFilterComparator {

  private MediaFilterComparator() {}

  public static Optional<String> findMismatch(MediaFilter cursorFilter, MediaFilter currentFilter) {
    if (cursorFilter.equals(currentFilter)) {
      return Optional.empty();
    }

    return checkField("sortBy", MediaFilter::getSortBy, cursorFilter, currentFilter)
        .or(
            () ->
                checkField(
                    "sortDirection", MediaFilter::getSortDirection, cursorFilter, currentFilter))
        .or(() -> checkField("libraryId", MediaFilter::getLibraryId, cursorFilter, currentFilter))
        .or(
            () ->
                checkField("startLetter", MediaFilter::getStartLetter, cursorFilter, currentFilter))
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
        .or(() -> checkField("unmatched", MediaFilter::getUnmatched, cursorFilter, currentFilter));
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
