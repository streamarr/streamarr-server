package com.streamarr.server.rest.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("JSON:API Page Adapter Tests")
class JsonApiPageAdapterTest {

  private final JsonApiCursorCodec codec = new JsonApiCursorCodec(new ObjectMapper());
  private final JsonApiPageAdapter adapter = new JsonApiPageAdapter(codec);

  private static final String BASE_URL = "http://localhost:8080/api/libraries/123/movies";

  @Test
  @DisplayName("Should return correct data with resource objects when page has items")
  void shouldReturnCorrectDataWithResourceObjectsWhenPageHasItems() {
    var id1 = UUID.randomUUID();
    var id2 = UUID.randomUUID();

    var page =
        new MediaPage<>(
            List.of(
                new PageItem<>(
                    Movie.builder()
                        .id(id1)
                        .title("Alpha")
                        .releaseDate(LocalDate.of(2024, 3, 15))
                        .build(),
                    "Alpha"),
                new PageItem<>(Movie.builder().id(id2).title("Beta").build(), "Beta")),
            true,
            false);

    var response = adapter.toResponse(page, buildOptions(), BASE_URL, 2, "movies");

    assertThat(response.data()).hasSize(2);
    assertThat(response.data().get(0).type()).isEqualTo("movies");
    assertThat(response.data().get(0).id()).isEqualTo(id1.toString());
    assertThat(response.data().get(0).attributes().get("title")).isEqualTo("Alpha");
    assertThat(response.data().get(1).attributes().get("title")).isEqualTo("Beta");
  }

  @Test
  @DisplayName("Should include per-item cursors in meta.page")
  void shouldIncludePerItemCursorsInMetaPage() {
    var page =
        new MediaPage<>(
            List.of(
                new PageItem<>(
                    Movie.builder().id(UUID.randomUUID()).title("Movie").build(), "Movie")),
            false,
            false);

    var response = adapter.toResponse(page, buildOptions(), BASE_URL, 10, "movies");

    assertThat(response.data().getFirst().meta()).isNotNull();
    assertThat(response.data().getFirst().meta().page()).isNotNull();
    assertThat(response.data().getFirst().meta().page().cursor()).isNotBlank();
  }

  @Test
  @DisplayName("Should include links.next when hasNextPage is true")
  void shouldIncludeLinksNextWhenHasNextPage() {
    var page =
        new MediaPage<>(
            List.of(new PageItem<>(Movie.builder().id(UUID.randomUUID()).title("A").build(), "A")),
            true,
            false);

    var response = adapter.toResponse(page, buildOptions(), BASE_URL, 2, "movies");

    assertThat(response.links().next()).isNotNull();
    assertThat(response.links().next()).contains("page[after]=");
    assertThat(response.links().next()).contains("page[size]=2");
    assertThat(response.links().prev()).isNull();
  }

  @Test
  @DisplayName("Should include links.prev when hasPreviousPage is true")
  void shouldIncludeLinksPrevWhenHasPreviousPage() {
    var page =
        new MediaPage<>(
            List.of(new PageItem<>(Movie.builder().id(UUID.randomUUID()).title("B").build(), "B")),
            false,
            true);

    var response = adapter.toResponse(page, buildOptions(), BASE_URL, 2, "movies");

    assertThat(response.links().prev()).isNotNull();
    assertThat(response.links().prev()).contains("page[before]=");
    assertThat(response.links().prev()).contains("page[size]=2");
    assertThat(response.links().next()).isNull();
  }

  @Test
  @DisplayName("Should include links.first always")
  void shouldIncludeLinksFirstAlways() {
    var page =
        new MediaPage<>(
            List.of(new PageItem<>(Movie.builder().id(UUID.randomUUID()).title("A").build(), "A")),
            false,
            false);

    var response = adapter.toResponse(page, buildOptions(), BASE_URL, 10, "movies");

    assertThat(response.links().first()).isEqualTo(BASE_URL + "?page[size]=10");
  }

  @Test
  @DisplayName("Should return empty data with null links when page is empty")
  void shouldReturnEmptyDataWithNullLinksWhenPageIsEmpty() {
    var page = new MediaPage<Movie>(List.of(), false, false);

    var response = adapter.toResponse(page, buildOptions(), BASE_URL, 10, "movies");

    assertThat(response.data()).isEmpty();
    assertThat(response.links().first()).isEqualTo(BASE_URL + "?page[size]=10");
    assertThat(response.links().next()).isNull();
    assertThat(response.links().prev()).isNull();
  }

  @Test
  @DisplayName("Should include both next and prev when both pages exist")
  void shouldIncludeBothNextAndPrevWhenBothPagesExist() {
    var page =
        new MediaPage<>(
            List.of(
                new PageItem<>(Movie.builder().id(UUID.randomUUID()).title("Mid").build(), "Mid")),
            true,
            true);

    var response = adapter.toResponse(page, buildOptions(), BASE_URL, 1, "movies");

    assertThat(response.links().next()).isNotNull();
    assertThat(response.links().prev()).isNotNull();
  }

  private MediaPaginationOptions buildOptions() {
    return MediaPaginationOptions.builder()
        .paginationOptions(
            PaginationOptions.builder()
                .cursor(Optional.empty())
                .paginationDirection(PaginationDirection.FORWARD)
                .limit(10)
                .build())
        .mediaFilter(MediaFilter.builder().build())
        .build();
  }
}
