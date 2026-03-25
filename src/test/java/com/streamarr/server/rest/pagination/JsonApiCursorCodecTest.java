package com.streamarr.server.rest.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.InvalidPaginationArgumentException;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.jooq.SortOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("JSON:API Cursor Codec Tests")
class JsonApiCursorCodecTest {

  private final JsonApiCursorCodec codec = new JsonApiCursorCodec(new ObjectMapper());

  @Nested
  @DisplayName("Encode/Decode Round-Trip")
  class EncodeDecodeRoundTrip {

    @Test
    @DisplayName("Should preserve cursorId and sort value when round-tripping encode and decode")
    void shouldPreserveCursorIdAndSortValueWhenRoundTripping() {
      var cursorId = UUID.randomUUID();
      var libraryId = UUID.randomUUID();

      var filter = MediaFilter.builder().libraryId(libraryId).sortBy(OrderMediaBy.TITLE).build();
      var options =
          MediaPaginationOptions.builder()
              .mediaFilter(filter)
              .paginationOptions(
                  PaginationOptions.builder()
                      .cursor(Optional.empty())
                      .paginationDirection(PaginationDirection.FORWARD)
                      .limit(10)
                      .build())
              .build();

      var encoded = codec.encode(options, cursorId, "Alpha");

      var decoded =
          codec.decode(
              PaginationOptions.builder()
                  .cursor(Optional.of(encoded))
                  .paginationDirection(PaginationDirection.FORWARD)
                  .limit(10)
                  .build());

      assertThat(decoded.getCursorId()).contains(cursorId);
      assertThat(decoded.getMediaFilter().getLibraryId()).isEqualTo(libraryId);
      assertThat(decoded.getMediaFilter().getSortBy()).isEqualTo(OrderMediaBy.TITLE);
      assertThat(decoded.getMediaFilter().getPreviousSortFieldValue()).isEqualTo("Alpha");
    }

    @Test
    @DisplayName("Should preserve all filter dimensions when round-tripping")
    void shouldPreserveAllFilterDimensionsWhenRoundTripping() {
      var cursorId = UUID.randomUUID();
      var genreId = UUID.randomUUID();

      var filter =
          MediaFilter.builder()
              .sortBy(OrderMediaBy.RELEASE_DATE)
              .sortDirection(SortOrder.DESC)
              .genreIds(java.util.List.of(genreId))
              .years(java.util.List.of(2024))
              .contentRatings(java.util.List.of("PG-13"))
              .unmatched(true)
              .build();

      var options =
          MediaPaginationOptions.builder()
              .mediaFilter(filter)
              .paginationOptions(
                  PaginationOptions.builder()
                      .cursor(Optional.empty())
                      .paginationDirection(PaginationDirection.FORWARD)
                      .limit(10)
                      .build())
              .build();

      var encoded = codec.encode(options, cursorId, "2024-01-15");

      var decoded =
          codec.decode(
              PaginationOptions.builder()
                  .cursor(Optional.of(encoded))
                  .paginationDirection(PaginationDirection.FORWARD)
                  .limit(10)
                  .build());

      assertThat(decoded.getMediaFilter().getSortBy()).isEqualTo(OrderMediaBy.RELEASE_DATE);
      assertThat(decoded.getMediaFilter().getSortDirection()).isEqualTo(SortOrder.DESC);
      assertThat(decoded.getMediaFilter().getGenreIds()).containsExactly(genreId);
      assertThat(decoded.getMediaFilter().getYears()).containsExactly(2024);
      assertThat(decoded.getMediaFilter().getContentRatings()).containsExactly("PG-13");
      assertThat(decoded.getMediaFilter().getUnmatched()).isTrue();
      assertThat(decoded.getMediaFilter().getPreviousSortFieldValue()).isEqualTo("2024-01-15");
    }

    @Test
    @DisplayName("Should produce URL-safe encoded cursor when filter contains complex data")
    void shouldProduceUrlSafeEncodedCursorWhenFilterContainsComplexData() {
      var filter =
          MediaFilter.builder()
              .libraryId(UUID.randomUUID())
              .sortBy(OrderMediaBy.RELEASE_DATE)
              .sortDirection(SortOrder.DESC)
              .genreIds(java.util.List.of(UUID.randomUUID()))
              .build();

      var options =
          MediaPaginationOptions.builder()
              .mediaFilter(filter)
              .paginationOptions(
                  PaginationOptions.builder()
                      .cursor(Optional.empty())
                      .paginationDirection(PaginationDirection.FORWARD)
                      .limit(10)
                      .build())
              .build();

      var encoded = codec.encode(options, UUID.randomUUID(), "2024-06-15");

      assertThat(encoded).matches("^[A-Za-z0-9_-]+$");
    }
  }

  @Nested
  @DisplayName("Decode Errors")
  class DecodeErrors {

    @Test
    @DisplayName("Should throw when decoding invalid cursor string")
    void shouldThrowWhenDecodingInvalidCursorString() {
      var options =
          PaginationOptions.builder()
              .cursor(Optional.of("not-valid-base64!!!"))
              .paginationDirection(PaginationDirection.FORWARD)
              .limit(10)
              .build();

      assertThatThrownBy(() -> codec.decode(options))
          .isInstanceOf(InvalidPaginationArgumentException.class);
    }

    @Test
    @DisplayName("Should throw when decoding empty cursor")
    void shouldThrowWhenDecodingEmptyCursor() {
      var options =
          PaginationOptions.builder()
              .cursor(Optional.empty())
              .paginationDirection(PaginationDirection.FORWARD)
              .limit(10)
              .build();

      assertThatThrownBy(() -> codec.decode(options))
          .isInstanceOf(InvalidPaginationArgumentException.class)
          .hasMessage("Cannot decode an empty cursor.");
    }

    @Test
    @DisplayName("Should throw when cursor contains valid Base64 but invalid JSON")
    void shouldThrowWhenCursorContainsValidBase64ButInvalidJson() {
      var invalidBase64 =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString("not-json".getBytes(StandardCharsets.UTF_8));

      var options =
          PaginationOptions.builder()
              .cursor(Optional.of(invalidBase64))
              .paginationDirection(PaginationDirection.FORWARD)
              .limit(10)
              .build();

      assertThatThrownBy(() -> codec.decode(options))
          .isInstanceOf(InvalidPaginationArgumentException.class);
    }
  }

  @Nested
  @DisplayName("Cursor Filter Validation")
  class CursorFilterValidation {

    @Test
    @DisplayName("Should throw when cursor filter libraryId does not match current request")
    void shouldThrowWhenCursorFilterLibraryIdMismatch() {
      var cursorLibraryId = UUID.randomUUID();
      var requestLibraryId = UUID.randomUUID();

      var cursorFilter = MediaFilter.builder().libraryId(cursorLibraryId).build();
      var requestFilter = MediaFilter.builder().libraryId(requestLibraryId).build();

      var decoded =
          MediaPaginationOptions.builder()
              .cursorId(UUID.randomUUID())
              .mediaFilter(cursorFilter)
              .build();

      assertThatThrownBy(() -> codec.validateCursorFilter(decoded, requestFilter))
          .isInstanceOf(InvalidPaginationArgumentException.class)
          .hasMessageContaining("libraryId");
    }

    @Test
    @DisplayName("Should not throw when cursor filter matches current request")
    void shouldNotThrowWhenCursorFilterMatchesRequest() {
      var libraryId = UUID.randomUUID();

      var filter = MediaFilter.builder().libraryId(libraryId).build();
      var decoded =
          MediaPaginationOptions.builder().cursorId(UUID.randomUUID()).mediaFilter(filter).build();

      assertThatNoException().isThrownBy(() -> codec.validateCursorFilter(decoded, filter));
    }
  }
}
