package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class CursorValidator {

  public void validateCursorAgainstFilter(
      MediaPaginationOptions decodedOptions, MediaFilter filter) {
    var previousFilter = decodedOptions.getMediaFilter();

    validateCursorField("sortBy", previousFilter.getSortBy(), filter.getSortBy());
    validateCursorField(
        "sortDirection", previousFilter.getSortDirection(), filter.getSortDirection());
    validateCursorField("libraryId", previousFilter.getLibraryId(), filter.getLibraryId());
    validateCursorField("startLetter", previousFilter.getStartLetter(), filter.getStartLetter());
    validateCursorField("genreIds", previousFilter.getGenreIds(), filter.getGenreIds());
    validateCursorField("years", previousFilter.getYears(), filter.getYears());
    validateCursorField(
        "contentRatings", previousFilter.getContentRatings(), filter.getContentRatings());
    validateCursorField("studioIds", previousFilter.getStudioIds(), filter.getStudioIds());
    validateCursorField("directorIds", previousFilter.getDirectorIds(), filter.getDirectorIds());
    validateCursorField(
        "castMemberIds", previousFilter.getCastMemberIds(), filter.getCastMemberIds());
    validateCursorField("unmatched", previousFilter.getUnmatched(), filter.getUnmatched());
  }

  <T> void validateCursorField(String fieldName, T prior, T current) {
    if (Objects.equals(prior, current)) {
      return;
    }

    throw new InvalidCursorException(
        "Prior query "
            + fieldName
            + " was '"
            + prior
            + "'"
            + " but new query "
            + fieldName
            + " is '"
            + current
            + "'");
  }
}
