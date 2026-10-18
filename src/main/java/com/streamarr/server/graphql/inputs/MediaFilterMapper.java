package com.streamarr.server.graphql.inputs;

import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.services.pagination.MediaFilter;
import java.util.List;
import java.util.UUID;

public final class MediaFilterMapper {
  private MediaFilterMapper() {}

  public static MediaFilter from(MediaFilterRequest request) {
    var filter = request.filter();
    var builder =
        MediaFilter.builder().libraryId(request.libraryId()).profileId(request.profileId());
    applySortOptions(builder, request.sort());
    if (filter != null) {
      builder
          .startLetter(filter.startLetter())
          .genreIds(parseUuidList(filter.genreIds()))
          .years(filter.years())
          .contentRatings(filter.contentRatings())
          .studioIds(parseUuidList(filter.studioIds()))
          .directorIds(parseUuidList(filter.directorIds()))
          .castMemberIds(parseUuidList(filter.castMemberIds()))
          .unmatched(filter.unmatched())
          .watchStatus(filter.watchStatus());
    }

    return builder.build();
  }

  private static void applySortOptions(
      MediaFilter.MediaFilterBuilder builder, MediaSortInput sort) {
    if (sort == null) {
      return;
    }

    if (sort.by() != null) {
      builder.sortBy(sort.by());
    }

    if (sort.direction() != null) {
      builder.sortDirection(sort.direction().toSortOrder());
    }
  }

  private static UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }

  private static List<UUID> parseUuidList(List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }

    return ids.stream().map(MediaFilterMapper::parseUuid).toList();
  }
}
