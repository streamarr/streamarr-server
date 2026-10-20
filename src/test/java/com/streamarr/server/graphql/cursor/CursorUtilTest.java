package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.SortDirection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Cursor Util Tests")
class CursorUtilTest {

  private final CursorUtil cursorUtil = new CursorUtil(new ObjectMapper());

  @Nested
  @DisplayName("Encode/Decode Round-Trip")
  class EncodeDecodeRoundTrip {

    @Test
    @DisplayName("Should preserve a keyset cursor id when round-tripping encode and decode")
    void shouldPreserveKeysetCursorIdWhenRoundTrippingEncodeAndDecode() {
      var cursorId = UUID.randomUUID();
      var encoded = cursorUtil.encodeKeysetCursor(cursorId);
      var paginationOptions =
          PaginationOptions.builder()
              .cursor(Optional.of(encoded.getValue()))
              .paginationDirection(PaginationDirection.FORWARD)
              .limit(10)
              .build();

      KeysetPaginationOptions decoded = cursorUtil.decodeKeysetCursor(paginationOptions);

      assertThat(decoded.getCursorId()).contains(cursorId);
      assertThat(decoded.getPaginationOptions()).isEqualTo(paginationOptions);
    }

    @Test
    @DisplayName("Should preserve an opaque cursor value when round-tripping encode and decode")
    void shouldPreserveOpaqueCursorValueWhenRoundTrippingEncodeAndDecode() {
      var value = "2026-08-22T12:00:00Z|00000000-0000-0000-0000-000000000001";

      var encoded = cursorUtil.encodeOpaqueCursor(value);

      assertThat(cursorUtil.decodeOpaqueCursor(encoded.getValue())).isEqualTo(value);
    }

    @Test
    @DisplayName("Should use unpadded URL-safe UTF-8 Base64 when encoding an opaque cursor")
    void shouldUseUnpaddedUrlSafeUtf8Base64WhenEncodingOpaqueCursor() {
      assertThat(cursorUtil.encodeOpaqueCursor("࠾࠿").getValue()).isEqualTo("4KC-4KC_");
      assertThat(cursorUtil.encodeOpaqueCursor("日本語 🎬").getValue())
          .isEqualTo("5pel5pys6KqeIPCfjqw");
    }

    @Test
    @DisplayName("Should recover UTF-8 text when decoding a known opaque cursor")
    void shouldRecoverUtf8TextWhenDecodingKnownOpaqueCursor() {
      assertThat(cursorUtil.decodeOpaqueCursor("4KC-4KC_")).isEqualTo("࠾࠿");
      assertThat(cursorUtil.decodeOpaqueCursor("5pel5pys6KqeIPCfjqw")).isEqualTo("日本語 🎬");
    }

    @Test
    @DisplayName(
        "Should preserve cursorId and all filter dimensions when round-tripping encode and decode")
    void shouldPreserveCursorIdAndAllFilterDimensionsWhenRoundTrippingEncodeAndDecode() {
      var cursorId = UUID.randomUUID();
      var libraryId = UUID.randomUUID();
      var profileId = UUID.randomUUID();
      var genreId = UUID.randomUUID();
      var studioId = UUID.randomUUID();
      var directorId = UUID.randomUUID();
      var castMemberId = UUID.randomUUID();

      var filter =
          MediaFilter.builder()
              .libraryId(libraryId)
              .profileId(profileId)
              .sortBy(OrderMediaBy.RELEASE_DATE)
              .sortDirection(SortDirection.DESC)
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
      assertThat(decoded.getMediaFilter().getProfileId()).isEqualTo(profileId);
      assertThat(decoded.getMediaFilter().getSortBy()).isEqualTo(OrderMediaBy.RELEASE_DATE);
      assertThat(decoded.getMediaFilter().getSortDirection()).isEqualTo(SortDirection.DESC);
      assertThat(decoded.getMediaFilter().getGenreIds()).containsExactly(genreId);
      assertThat(decoded.getMediaFilter().getYears()).containsExactly(2024);
      assertThat(decoded.getMediaFilter().getContentRatings()).containsExactly("PG-13");
      assertThat(decoded.getMediaFilter().getStudioIds()).containsExactly(studioId);
      assertThat(decoded.getMediaFilter().getDirectorIds()).containsExactly(directorId);
      assertThat(decoded.getMediaFilter().getCastMemberIds()).containsExactly(castMemberId);
      assertThat(decoded.getMediaFilter().getUnmatched()).isTrue();
    }

    @Test
    @DisplayName("Should handle minimal filter with only defaults when round-tripping")
    void shouldHandleMinimalFilterWithOnlyDefaultsWhenRoundTripping() {
      var cursorId = UUID.randomUUID();
      var filter = MediaFilter.builder().build();

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

      var encoded = cursorUtil.encodeMediaCursor(options, cursorId, "sortVal");

      var decoded =
          cursorUtil.decodeMediaCursor(
              PaginationOptions.builder()
                  .cursor(Optional.of(encoded.getValue()))
                  .paginationDirection(PaginationDirection.FORWARD)
                  .limit(10)
                  .build());

      assertThat(decoded.getCursorId()).contains(cursorId);
      assertThat(decoded.getMediaFilter().getSortBy()).isEqualTo(OrderMediaBy.TITLE);
      assertThat(decoded.getMediaFilter().getSortDirection()).isEqualTo(SortDirection.ASC);
      assertThat(decoded.getMediaFilter().getLibraryId()).isNull();
      assertThat(decoded.getMediaFilter().getGenreIds()).isNull();
      assertThat(decoded.getMediaFilter().getYears()).isNull();
      assertThat(decoded.getMediaFilter().getContentRatings()).isNull();
      assertThat(decoded.getMediaFilter().getStudioIds()).isNull();
      assertThat(decoded.getMediaFilter().getDirectorIds()).isNull();
      assertThat(decoded.getMediaFilter().getCastMemberIds()).isNull();
      assertThat(decoded.getMediaFilter().getUnmatched()).isNull();
    }
  }

  @Nested
  @DisplayName("Decode Errors")
  class DecodeErrors {

    @Test
    @DisplayName("Should throw InvalidCursorException when a keyset cursor is malformed")
    void shouldThrowInvalidCursorExceptionWhenKeysetCursorIsMalformed() {
      var paginationOptions =
          PaginationOptions.builder()
              .cursor(Optional.of("not-base64!!!"))
              .paginationDirection(PaginationDirection.FORWARD)
              .limit(10)
              .build();

      assertThatThrownBy(() -> cursorUtil.decodeKeysetCursor(paginationOptions))
          .isInstanceOf(InvalidCursorException.class);
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "{\"cursorId\":\"5d2f6a8e-0c1b-4f3e-9a7d-2b4c6e8f0a1c\"}",
          "{\"mediaFilter\":{\"sortBy\":\"TITLE\",\"sortDirection\":\"ASC\"}}",
          "{\"cursorId\":\"5d2f6a8e-0c1b-4f3e-9a7d-2b4c6e8f0a1c\",\"mediaFilter\":"
              + "{\"sortBy\":\"LAST_WATCHED\",\"sortDirection\":\"DESC\","
              + "\"previousSortFieldValue\":\"yesterday\"}}",
          "{\"cursorId\":\"5d2f6a8e-0c1b-4f3e-9a7d-2b4c6e8f0a1c\",\"mediaFilter\":"
              + "{\"sortBy\":\"ADDED\",\"sortDirection\":\"ASC\","
              + "\"previousSortFieldValue\":\"+10000-01-01T00:00:00Z\"}}",
          "{\"cursorId\":\"5d2f6a8e-0c1b-4f3e-9a7d-2b4c6e8f0a1c\",\"mediaFilter\":"
              + "{\"sortBy\":\"RELEASE_DATE\",\"sortDirection\":\"ASC\","
              + "\"previousSortFieldValue\":\"someday\"}}",
          "{\"cursorId\":\"5d2f6a8e-0c1b-4f3e-9a7d-2b4c6e8f0a1c\",\"mediaFilter\":"
              + "{\"sortBy\":\"RUNTIME\",\"sortDirection\":\"ASC\","
              + "\"previousSortFieldValue\":\"ninety\"}}",
          "{\"cursorId\":\"5d2f6a8e-0c1b-4f3e-9a7d-2b4c6e8f0a1c\",\"mediaFilter\":"
              + "{\"sortBy\":null,\"sortDirection\":\"ASC\"}}"
        })
    @DisplayName("Should throw InvalidCursorException when a media cursor cannot be read")
    void shouldThrowInvalidCursorExceptionWhenAMediaCursorCannotBeRead(String json) {
      var cursor =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(json.getBytes(StandardCharsets.UTF_8));

      assertThatThrownBy(() -> cursorUtil.decodeMediaCursor(forwardFrom(cursor)))
          .isInstanceOf(InvalidCursorException.class);
    }

    private static PaginationOptions forwardFrom(String cursor) {
      return PaginationOptions.builder()
          .cursor(Optional.of(cursor))
          .paginationDirection(PaginationDirection.FORWARD)
          .limit(10)
          .build();
    }
  }

  @Nested
  @DisplayName("Typed Sort Values")
  class TypedSortValues {

    @ParameterizedTest(name = "{0}")
    @MethodSource("sortValuesOfEachSortField")
    @DisplayName("Should decode a sort value as its sort field's type when the cursor round-trips")
    void shouldDecodeASortValueAsItsSortFieldsTypeWhenTheCursorRoundTrips(
        OrderMediaBy sortBy, Object sortValue) {
      var options =
          MediaPaginationOptions.builder()
              .mediaFilter(MediaFilter.builder().sortBy(sortBy).build())
              .build();
      var cursor = cursorUtil.encodeMediaCursor(options, UUID.randomUUID(), sortValue);

      var decoded =
          cursorUtil.decodeMediaCursor(
              PaginationOptions.builder()
                  .cursor(Optional.of(cursor.getValue()))
                  .paginationDirection(PaginationDirection.FORWARD)
                  .limit(10)
                  .build());

      assertThat(decoded.getMediaFilter().getPreviousSortFieldValue()).isEqualTo(sortValue);
    }

    static Stream<Arguments> sortValuesOfEachSortField() {
      return Stream.of(
          Arguments.of(OrderMediaBy.TITLE, "Alpha"),
          Arguments.of(OrderMediaBy.ADDED, Instant.parse("2026-10-19T15:07:00Z")),
          Arguments.of(OrderMediaBy.RELEASE_DATE, LocalDate.of(2020, 1, 1)),
          Arguments.of(OrderMediaBy.RUNTIME, 90),
          Arguments.of(OrderMediaBy.LAST_WATCHED, Instant.parse("2026-10-19T15:07:12.345Z")));
    }
  }
}
