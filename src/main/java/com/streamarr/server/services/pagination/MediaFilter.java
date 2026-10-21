package com.streamarr.server.services.pagination;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.streaming.WatchStatus;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@EqualsAndHashCode
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

  /** How the Library lists its items under this filter. */
  public ListOrder listOrder() {
    return ListOrder.builder()
        .valueKind(sortBy.getValueKind())
        .direction(sortDirection)
        .absentValues(sortBy.getAbsentValues())
        .build();
  }

  /**
   * The filter naming which items the list holds, written one way for each set of items. A missing
   * restriction keeps every item, as an empty one does, so it is written as empty. Under TITLE sort
   * a start letter only chooses where the first page lands, so the list and its cursors leave it
   * out; under every other sort it keeps the items whose title starts with that letter.
   */
  public MediaFilter listScope() {
    var scope =
        toBuilder()
            .genreIds(restriction(genreIds))
            .years(restriction(years))
            .contentRatings(restriction(contentRatings))
            .studioIds(restriction(studioIds))
            .directorIds(restriction(directorIds))
            .castMemberIds(restriction(castMemberIds));
    if (sortBy == OrderMediaBy.TITLE) {
      scope.startLetter(null);
    }

    return scope.build();
  }

  private static <T> List<T> restriction(List<T> values) {
    return Objects.requireNonNullElse(values, List.of());
  }
}
