package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Library Item Cursors Tests")
class LibraryItemCursorsTest {

  private static final MediaFilter ISSUED_UNDER =
      MediaFilter.builder()
          .libraryId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
          .profileId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
          .genreIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000003")))
          .years(List.of(2024))
          .contentRatings(List.of("PG-13"))
          .studioIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000004")))
          .directorIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000005")))
          .castMemberIds(List.of(UUID.fromString("00000000-0000-0000-0000-000000000006")))
          .unmatched(false)
          .watchStatus(WatchStatus.IN_PROGRESS)
          .build();

  private static final Position POSITION =
      new Position(new SortValue.OfText("inception"), UUID.randomUUID());

  private final PositionCursorCodec cursors = new PositionCursorCodec(new ObjectMapper());

  @Test
  @DisplayName("Should continue a cursor when it is read under the filter that issued it")
  void shouldContinueACursorWhenItIsReadUnderTheFilterThatIssuedIt() {
    var cursor = issue(ISSUED_UNDER);

    assertThat(cursors.decode(LibraryItemCursors.listOf(ISSUED_UNDER.toBuilder().build()), cursor))
        .isEqualTo(POSITION);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("filterChanges")
  @DisplayName("Should reject a cursor as invalid when it is read under a different filter")
  void shouldRejectACursorAsInvalidWhenItIsReadUnderADifferentFilter(
      String change, UnaryOperator<MediaFilter.MediaFilterBuilder> changed) {
    var cursor = issue(ISSUED_UNDER);
    var readUnder = LibraryItemCursors.listOf(changed.apply(ISSUED_UNDER.toBuilder()).build());

    assertThatThrownBy(() -> cursors.decode(readUnder, cursor))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName(
      "Should continue a cursor when one request leaves out a restriction another sends empty")
  void shouldContinueACursorWhenOneRequestLeavesOutARestrictionAnotherSendsEmpty() {
    var unrestricted = MediaFilter.builder().libraryId(ISSUED_UNDER.getLibraryId()).build();
    var cursor =
        issue(
            unrestricted.toBuilder()
                .genreIds(List.of())
                .years(List.of())
                .contentRatings(List.of())
                .studioIds(List.of())
                .directorIds(List.of())
                .castMemberIds(List.of())
                .build());

    assertThat(cursors.decode(LibraryItemCursors.listOf(unrestricted), cursor)).isEqualTo(POSITION);
  }

  @Test
  @DisplayName("Should continue a title cursor when a later request drops the start letter")
  void shouldContinueATitleCursorWhenALaterRequestDropsTheStartLetter() {
    var cursor = issue(ISSUED_UNDER.toBuilder().startLetter(AlphabetLetter.B).build());

    assertThat(cursors.decode(LibraryItemCursors.listOf(ISSUED_UNDER), cursor)).isEqualTo(POSITION);
  }

  @Test
  @DisplayName("Should reject a cursor as invalid when the start letter changes under another sort")
  void shouldRejectACursorAsInvalidWhenTheStartLetterChangesUnderAnotherSort() {
    var added = ISSUED_UNDER.toBuilder().sortBy(OrderMediaBy.ADDED);
    var cursor = issue(added.startLetter(AlphabetLetter.A).build());
    var readUnder = LibraryItemCursors.listOf(added.startLetter(AlphabetLetter.B).build());

    assertThatThrownBy(() -> cursors.decode(readUnder, cursor))
        .isInstanceOf(InvalidCursorException.class);
  }

  private String issue(MediaFilter filter) {
    return cursors.encode(LibraryItemCursors.listOf(filter), POSITION).getValue();
  }

  private static Stream<Arguments> filterChanges() {
    return Stream.of(
        change("library", b -> b.libraryId(UUID.randomUUID())),
        change("profile", b -> b.profileId(UUID.randomUUID())),
        change("sort field", b -> b.sortBy(OrderMediaBy.RELEASE_DATE)),
        change("sort direction", b -> b.sortDirection(SortDirection.DESC)),
        change("genres", b -> b.genreIds(List.of(UUID.randomUUID()))),
        change("no genres", b -> b.genreIds(null)),
        change("years", b -> b.years(List.of(1999))),
        change("content ratings", b -> b.contentRatings(List.of("R"))),
        change("studios", b -> b.studioIds(List.of(UUID.randomUUID()))),
        change("directors", b -> b.directorIds(List.of(UUID.randomUUID()))),
        change("cast members", b -> b.castMemberIds(List.of(UUID.randomUUID()))),
        change("unmatched", b -> b.unmatched(true)),
        change("watch status", b -> b.watchStatus(WatchStatus.WATCHED)));
  }

  private static Arguments change(
      String change, UnaryOperator<MediaFilter.MediaFilterBuilder> changed) {
    return Arguments.of(change, changed);
  }
}
