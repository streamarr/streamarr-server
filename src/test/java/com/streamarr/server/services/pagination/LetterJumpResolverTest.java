package com.streamarr.server.services.pagination;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.media.Movie;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Letter Jump Resolver Tests")
class LetterJumpResolverTest {

  private final Movie predecessor = buildPredecessor();

  private static Movie buildPredecessor() {
    var movie = Movie.builder().title("Avengers").titleSort("Avengers").build();
    movie.setId(UUID.randomUUID());
    return movie;
  }

  @Test
  @DisplayName("Should anchor at predecessor when letter jump has a predecessor")
  void shouldAnchorAtPredecessorWhenLetterJumpHasPredecessor() {
    var filter =
        MediaFilter.builder().libraryId(UUID.randomUUID()).startLetter(AlphabetLetter.B).build();
    var options = buildForwardOptions(10, filter);

    var resolved = LetterJumpResolver.resolve(options, _ -> Optional.of(predecessor));

    assertThat(resolved.getCursorId()).contains(predecessor.getId());
    assertThat(resolved.getMediaFilter().getPreviousSortFieldValue())
        .isEqualTo(predecessor.getTitleSort());
    assertThat(resolved.getMediaFilter().getStartLetter()).isEqualTo(AlphabetLetter.B);
    assertThat(resolved.getMediaFilter().getLibraryId()).isEqualTo(filter.getLibraryId());
    assertThat(resolved.getPaginationOptions()).isEqualTo(options.getPaginationOptions());
  }

  @Test
  @DisplayName("Should leave options unchanged when no predecessor exists above the letter")
  void shouldLeaveOptionsUnchangedWhenNoPredecessorExistsAboveTheLetter() {
    var filter = MediaFilter.builder().startLetter(AlphabetLetter.A).build();
    var options = buildForwardOptions(10, filter);

    var resolved = LetterJumpResolver.resolve(options, _ -> Optional.<Movie>empty());

    assertThat(resolved).isSameAs(options);
  }

  @Test
  @DisplayName("Should leave options unchanged when a cursor is already present")
  void shouldLeaveOptionsUnchangedWhenCursorIsAlreadyPresent() {
    var filter = MediaFilter.builder().startLetter(AlphabetLetter.B).build();
    var options = buildForwardOptions(10, filter).toBuilder().cursorId(UUID.randomUUID()).build();

    var resolved = LetterJumpResolver.resolve(options, _ -> Optional.of(predecessor));

    assertThat(resolved).isSameAs(options);
  }

  @Test
  @DisplayName("Should leave options unchanged when no start letter is requested")
  void shouldLeaveOptionsUnchangedWhenNoStartLetterIsRequested() {
    var options = buildForwardOptions(10, MediaFilter.builder().build());

    var resolved = LetterJumpResolver.resolve(options, _ -> Optional.of(predecessor));

    assertThat(resolved).isSameAs(options);
  }

  @Test
  @DisplayName("Should leave options unchanged when sort is not TITLE")
  void shouldLeaveOptionsUnchangedWhenSortIsNotTitle() {
    var filter =
        MediaFilter.builder().sortBy(OrderMediaBy.ADDED).startLetter(AlphabetLetter.B).build();
    var options = buildForwardOptions(10, filter);

    var resolved = LetterJumpResolver.resolve(options, _ -> Optional.of(predecessor));

    assertThat(resolved).isSameAs(options);
  }
}
