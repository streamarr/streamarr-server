package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import com.streamarr.server.utils.TitleSortUtil;
import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("IntegrationTest")
@DisplayName("Live-list conformance fixtures")
class LiveListContractIT extends AbstractIntegrationTest {

  private static final Path CONTRACT = Path.of("contracts", "live-lists.json");

  @LocalServerPort private int port;
  @Autowired private AuthTestSupport auth;
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;

  static Stream<JsonNode> libraryOrderings() {
    var scenarios = new ObjectMapper().readTree(CONTRACT.toFile()).path("library");
    return scenarios
        .valueStream()
        .flatMap(
            scenario ->
                Stream.of(
                    scenario.path("initial"), scenario.path("recovered"), withFollowUp(scenario)));
  }

  @ParameterizedTest
  @MethodSource("libraryOrderings")
  @DisplayName("Should order a library fixture's titles as the server does when they are saved")
  void shouldOrderALibraryFixturesTitlesAsTheServerDoesWhenTheyAreSaved(JsonNode entries) {
    var fixtureOrder = entries.valueStream().map(entry -> entry.path("id").asString()).toList();
    assertThat(fixtureOrder)
        .as("entries listed in ascending fixture order key")
        .isEqualTo(
            entries
                .valueStream()
                .sorted(Comparator.comparing(entry -> entry.path("orderKey").asString()))
                .map(entry -> entry.path("id").asString())
                .toList());

    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var fixtureIdByMovieId = new HashMap<String, String>();
    entries.forEach(
        entry -> {
          var title = entry.path("title").asString();
          var movie =
              movies.saveAndFlush(
                  Movie.builder()
                      .title(title)
                      .titleSort(TitleSortUtil.computeTitleSort(title))
                      .library(library)
                      .build());
          fixtureIdByMovieId.put(movie.getId().toString(), entry.path("id").asString());
        });

    var identity = auth.createIdentity();
    try (var http =
        new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)))) {
      var result =
          http.execute(
              auth.profileBearer(identity),
              """
              { library(id: "%s") { items(first: 50, sort: {by: TITLE, direction: ASC}) {
                edges { node { ... on Movie { id } } }
              } } }
              """
                  .formatted(library.getId()));

      assertThat(result.has("errors")).as(result.toPrettyString()).isFalse();
      assertThat(
              result
                  .at("/data/library/items/edges")
                  .valueStream()
                  .map(edge -> fixtureIdByMovieId.get(edge.at("/node/id").asString()))
                  .toList())
          .isEqualTo(fixtureOrder);
    } finally {
      auth.deleteIdentity(identity);
    }
  }

  private static JsonNode withFollowUp(JsonNode scenario) {
    var entries = new ObjectMapper().createArrayNode();
    scenario.path("recovered").forEach(entries::add);
    entries.add(scenario.path("followUp"));
    var sorted = new ObjectMapper().createArrayNode();
    entries
        .valueStream()
        .sorted(Comparator.comparing(entry -> entry.path("orderKey").asString()))
        .forEach(sorted::add);
    return sorted;
  }
}
