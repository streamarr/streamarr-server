package com.streamarr.server.services.pagination;

import com.streamarr.server.domain.AlphabetLetter;
import java.util.List;
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
  private AlphabetLetter startLetter;

  private List<UUID> genreIds;
  private List<Integer> years;
  private List<String> contentRatings;
  private List<UUID> studioIds;
  private List<UUID> directorIds;
  private List<UUID> castMemberIds;
  private Boolean unmatched;

  // Used for seek pagination
  private Object previousSortFieldValue;
}
