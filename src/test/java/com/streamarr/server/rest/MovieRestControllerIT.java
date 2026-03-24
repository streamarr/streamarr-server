package com.streamarr.server.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.rest.pagination.JsonApiPageResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@AutoConfigureMockMvc
@DisplayName("Movie REST Controller Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MovieRestControllerIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private MovieRepository movieRepository;

  @Autowired private LibraryRepository libraryRepository;

  private Library savedLibrary;
  private Library emptyLibrary;

  @BeforeAll
  void setup() {
    var library = LibraryFixtureCreator.buildFakeLibrary();
    savedLibrary = libraryRepository.saveAndFlush(library);

    movieRepository.saveAndFlush(
        Movie.builder().title("Alpha").titleSort("Alpha").library(savedLibrary).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Beta").titleSort("Beta").library(savedLibrary).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Gamma").titleSort("Gamma").library(savedLibrary).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Delta").titleSort("Delta").library(savedLibrary).build());

    var emptyLib = LibraryFixtureCreator.buildFakeLibrary();
    emptyLibrary = libraryRepository.saveAndFlush(emptyLib);
  }

  @Test
  @DisplayName("Should return first page with next link when given page size")
  void shouldReturnFirstPageWithNextLinkWhenGivenPageSize() throws Exception {
    var page = fetchPage(buildUrl(savedLibrary.getId(), 2));

    assertThat(page.data()).hasSize(2);
    assertThat(page.links().next()).isNotNull();
    assertThat(page.links().prev()).isNull();
    assertThat(page.links().first()).isNotNull();
  }

  @Test
  @DisplayName("Should return second page when following next link")
  void shouldReturnSecondPageWhenFollowingNextLink() throws Exception {
    var firstPage = fetchPage(buildUrl(savedLibrary.getId(), 2));
    assertThat(firstPage.links().next()).isNotNull();

    var secondPage = fetchPage(firstPage.links().next());

    assertThat(secondPage.data()).hasSize(2);
    assertThat(secondPage.links().prev()).isNotNull();

    var firstPageTitles = titles(firstPage);
    var secondPageTitles = titles(secondPage);

    assertThat(secondPageTitles).isNotEmpty();
    assertThat(firstPageTitles).doesNotContainAnyElementsOf(secondPageTitles);
  }

  @Test
  @DisplayName("Should paginate backward when given page before")
  void shouldPaginateBackwardWhenGivenPageBefore() throws Exception {
    var firstPage = fetchPage(buildUrl(savedLibrary.getId(), 2));
    var secondPage = fetchPage(firstPage.links().next());
    assertThat(secondPage.links().prev()).isNotNull();

    var backPage = fetchPage(secondPage.links().prev());

    assertThat(backPage.data()).isNotEmpty();
    assertThat(backPage.links().next()).isNotNull();
  }

  @Test
  @DisplayName("Should cover all items with no duplicates when paginating forward")
  void shouldCoverAllItemsWithNoDuplicatesWhenPaginatingForward() throws Exception {
    List<String> allTitles = new ArrayList<>();
    var page = fetchPage(buildUrl(savedLibrary.getId(), 2));

    while (page != null) {
      allTitles.addAll(titles(page));

      if (page.links().next() == null) {
        break;
      }
      page = fetchPage(page.links().next());
    }

    assertThat(allTitles)
        .hasSize(4)
        .doesNotHaveDuplicates()
        .containsExactly("Alpha", "Beta", "Delta", "Gamma");
  }

  @Test
  @DisplayName("Should maintain canonical order when paginating backward")
  void shouldMaintainCanonicalOrderWhenPaginatingBackward() throws Exception {
    var allForward = fetchPage(buildUrl(savedLibrary.getId(), 10));
    var forwardTitles = titles(allForward);

    var lastPage = fetchPage(buildUrl(savedLibrary.getId(), 2));
    while (lastPage.links().next() != null) {
      lastPage = fetchPage(lastPage.links().next());
    }

    assertThat(lastPage.links().prev()).isNotNull();
    var prevPage = fetchPage(lastPage.links().prev());
    var backwardTitles = titles(prevPage);

    assertThat(forwardTitles).containsAll(backwardTitles);
  }

  @Test
  @DisplayName("Should return only movies from specified library")
  void shouldReturnOnlyMoviesFromSpecifiedLibrary() throws Exception {
    var page = fetchPage(buildUrl(savedLibrary.getId(), 10));
    assertThat(page.data()).hasSize(4);
  }

  @Test
  @DisplayName("Should include per-item cursors in meta.page")
  void shouldIncludePerItemCursorsInMetaPage() throws Exception {
    var page = fetchPage(buildUrl(savedLibrary.getId(), 10));

    page.data()
        .forEach(
            resource -> {
              assertThat(resource.meta()).isNotNull();
              assertThat(resource.meta().page()).isNotNull();
              assertThat(resource.meta().page().cursor()).isNotBlank();
            });
  }

  @Test
  @DisplayName("Should return valid JSON:API resource objects")
  void shouldReturnValidJsonApiResourceObjects() throws Exception {
    var page = fetchPage(buildUrl(savedLibrary.getId(), 10));

    page.data()
        .forEach(
            resource -> {
              assertThat(resource.type()).isEqualTo("movies");
              assertThat(resource.id()).isNotBlank();
              assertThat(resource.attributes()).containsKey("title");
            });
  }

  @Test
  @DisplayName("Should return 400 when page size is negative")
  void shouldReturn400WhenPageSizeIsNegative() throws Exception {
    mockMvc
        .perform(get(buildBaseUrl(savedLibrary.getId()) + "?page[size]=-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Should return 400 when page size exceeds max")
  void shouldReturn400WhenPageSizeExceedsMax() throws Exception {
    mockMvc
        .perform(get(buildBaseUrl(savedLibrary.getId()) + "?page[size]=501"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Should return application/vnd.api+json content type")
  void shouldReturnApplicationVndApiJsonContentType() throws Exception {
    mockMvc
        .perform(get(buildUrl(savedLibrary.getId(), 2)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/vnd.api+json"));
  }

  @Test
  @DisplayName("Should return empty data with null links when library has no movies")
  void shouldReturnEmptyDataWithNullLinksWhenLibraryHasNoMovies() throws Exception {
    var page = fetchPage(buildUrl(emptyLibrary.getId(), 10));

    assertThat(page.data()).isEmpty();
    assertThat(page.links().next()).isNull();
    assertThat(page.links().prev()).isNull();
    assertThat(page.links().first()).isNotNull();
  }

  private JsonApiPageResponse fetchPage(String url) throws Exception {
    var result =
        mockMvc
            .perform(get(url))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return objectMapper.readValue(result, JsonApiPageResponse.class);
  }

  private List<String> titles(JsonApiPageResponse page) {
    return page.data().stream().map(r -> (String) r.attributes().get("title")).toList();
  }

  private String buildUrl(java.util.UUID libraryId, int pageSize) {
    return buildBaseUrl(libraryId) + "?page[size]=" + pageSize;
  }

  private String buildBaseUrl(java.util.UUID libraryId) {
    return "/api/libraries/" + libraryId + "/movies";
  }
}
