package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.SortOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Cursor Util Tests")
class CursorUtilTest {

  private final CursorUtil cursorUtil = new CursorUtil(new ObjectMapper());

  @Test
  @DisplayName(
      "Should preserve cursorId and all filter dimensions when round-tripping encode and decode")
  void shouldPreserveCursorIdAndAllFilterDimensionsWhenRoundTrippingEncodeAndDecode() {
    var cursorId = UUID.randomUUID();
    var libraryId = UUID.randomUUID();
    var genreId = UUID.randomUUID();
    var studioId = UUID.randomUUID();
    var directorId = UUID.randomUUID();
    var castMemberId = UUID.randomUUID();

    var filter =
        MediaFilter.builder()
            .libraryId(libraryId)
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.DESC)
            .genreIds(List.of(genreId))
            .years(List.of(2024))
            .contentRatings(List.of("PG-13"))
            .studioIds(List.of(studioId))
            .directorIds(List.of(directorId))
            .castMemberIds(List.of(castMemberId))
            .unmatched(true)
            .build();

    var options =
        MediaPaginationOptions.builder()
            .cursorId(cursorId)
            .mediaFilter(filter)
            .paginationOptions(
                PaginationOptions.builder()
                    .cursor(Optional.empty())
                    .paginationDirection(PaginationDirection.FORWARD)
                    .limit(10)
                    .build())
            .build();

    var encoded = cursorUtil.encodeMediaCursor(options, cursorId, "2024-01-15");

    var paginationOptions =
        PaginationOptions.builder()
            .cursor(Optional.of(encoded.getValue()))
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(10)
            .build();

    var decoded = cursorUtil.decodeMediaCursor(paginationOptions);

    assertThat(decoded.getCursorId()).contains(cursorId);
    assertThat(decoded.getMediaFilter().getLibraryId()).isEqualTo(libraryId);
    assertThat(decoded.getMediaFilter().getSortBy()).isEqualTo(OrderMediaBy.RELEASE_DATE);
    assertThat(decoded.getMediaFilter().getSortDirection()).isEqualTo(SortOrder.DESC);
    assertThat(decoded.getMediaFilter().getGenreIds()).containsExactly(genreId);
    assertThat(decoded.getMediaFilter().getYears()).containsExactly(2024);
    assertThat(decoded.getMediaFilter().getContentRatings()).containsExactly("PG-13");
    assertThat(decoded.getMediaFilter().getStudioIds()).containsExactly(studioId);
    assertThat(decoded.getMediaFilter().getDirectorIds()).containsExactly(directorId);
    assertThat(decoded.getMediaFilter().getCastMemberIds()).containsExactly(castMemberId);
    assertThat(decoded.getMediaFilter().getUnmatched()).isTrue();
  }

  @Test
  @DisplayName("Should throw InvalidCursorException when cursor string is malformed Base64")
  void shouldThrowInvalidCursorExceptionWhenCursorStringIsMalformedBase64() {
    var paginationOptions =
        PaginationOptions.builder()
            .cursor(Optional.of("not-base64!!!"))
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(10)
            .build();

    assertThatThrownBy(() -> cursorUtil.decodeMediaCursor(paginationOptions))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("Should throw InvalidCursorException when cursor is empty")
  void shouldThrowInvalidCursorExceptionWhenCursorIsEmpty() {
    var paginationOptions =
        PaginationOptions.builder()
            .cursor(Optional.empty())
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(10)
            .build();

    assertThatThrownBy(() -> cursorUtil.decodeMediaCursor(paginationOptions))
        .isInstanceOf(InvalidCursorException.class)
        .hasMessage("Cannot decode an empty cursor.");
  }

  @Test
  @DisplayName("Should throw InvalidCursorException when cursor contains invalid JSON")
  void shouldThrowInvalidCursorExceptionWhenCursorContainsInvalidJson() {
    var invalidBase64 =
        Base64.getEncoder().encodeToString("not-json".getBytes(StandardCharsets.UTF_8));

    var paginationOptions =
        PaginationOptions.builder()
            .cursor(Optional.of(invalidBase64))
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(10)
            .build();

    assertThatThrownBy(() -> cursorUtil.decodeMediaCursor(paginationOptions))
        .isInstanceOf(InvalidCursorException.class);
  }
}
