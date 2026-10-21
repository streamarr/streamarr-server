package com.streamarr.server.services.pagination;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.streaming.WatchStatus;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@EqualsAndHashCode(exclude = "previousSortFieldValue")
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MediaFilter {

  @Builder.Default private final OrderMediaBy sortBy = OrderMediaBy.TITLE;
  @Builder.Default private final SortDirection sortDirection = SortDirection.ASC;

  private UUID libraryId;
  private UUID profileId;
  private AlphabetLetter startLetter;

  private List<UUID> genreIds;
  private List<Integer> years;
  private List<String> contentRatings;
  private List<UUID> studioIds;
  private List<UUID> directorIds;
  private List<UUID> castMemberIds;
  private Boolean unmatched;
  private WatchStatus watchStatus;

  private Object previousSortFieldValue;
}
