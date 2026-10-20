package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("IntegrationTest")
@DisplayName("Library items cursor validation")
class LibraryItemsCursorIT extends AbstractIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort private int port;
  @Autowired private AuthTestSupport auth;
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;

  @ParameterizedTest(name = "{0} cursor with sort value {1}")
  @CsvSource({
    "LAST_WATCHED, yesterday",
    "LAST_WATCHED, +294277-01-01T00:00:00Z",
    "ADDED, yesterday",
    "ADDED, -0001-01-01T00:00:00Z",
    "LAST_WATCHED, 0000-12-31T23:59:59Z",
    "RELEASE_DATE, someday",
    "RELEASE_DATE, +5874898-01-01",
    "RELEASE_DATE, 0000-12-31",
    "RUNTIME, ninety"
  })
  @DisplayName("Should reject a cursor as INVALID_CURSOR when its sort value cannot be read")
  void shouldRejectACursorAsInvalidWhenItsSortValueCannotBeRead(String sort, String value) {
    assertForgedCursorIsInvalid(
        sort, cursor -> mediaFilter(cursor).put("previousSortFieldValue", value));
  }

  @ParameterizedTest(name = "{0} cursor without a filter")
  @CsvSource({"TITLE", "LAST_WATCHED"})
  @DisplayName("Should reject a cursor as INVALID_CURSOR when it carries no filter")
  void shouldRejectACursorAsInvalidWhenItCarriesNoFilter(String sort) {
    assertForgedCursorIsInvalid(sort, cursor -> cursor.remove("mediaFilter"));
  }

  @ParameterizedTest(name = "{0} cursor without a sort field")
  @CsvSource({"TITLE", "RUNTIME"})
  @DisplayName("Should reject a cursor as INVALID_CURSOR when it names no sort field")
  void shouldRejectACursorAsInvalidWhenItNamesNoSortField(String sort) {
    assertForgedCursorIsInvalid(sort, cursor -> mediaFilter(cursor).putNull("sortBy"));
  }

  @Test
  @DisplayName("Should follow a RELEASE_DATE cursor when its item is released after year 9999")
  void shouldFollowAReleaseDateCursorWhenItsItemIsReleasedAfterYear9999() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var released = new LocalDate[] {LocalDate.of(10000, 1, 1), LocalDate.of(10001, 1, 1)};
    var later =
        movies.saveAndFlush(
            Movie.builder()
                .title("Bravo")
                .titleSort("Bravo")
                .releaseDate(released[1])
                .library(library)
                .build());
    movies.saveAndFlush(
        Movie.builder()
            .title("Alpha")
            .titleSort("Alpha")
            .releaseDate(released[0])
            .library(library)
            .build());

    var identity = auth.createIdentity();
    var bearer = auth.profileBearer(identity);
    try (var http =
        new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)))) {
      var query =
          """
          { library(id: "%s") { items(%%s, sort: {by: RELEASE_DATE, direction: ASC}) {
            edges { cursor node { ... on Movie { id } } }
          } } }
          """
              .formatted(library.getId());
      var first = http.execute(bearer, query.formatted("first: 1"));
      var cursor = first.at("/data/library/items/edges/0/cursor").asString();

      var next = http.execute(bearer, query.formatted("first: 1, after: \"%s\"".formatted(cursor)));

      assertThat(next.has("errors")).as(next.toPrettyString()).isFalse();
      assertThat(next.at("/data/library/items/edges/0/node/id").asString())
          .isEqualTo(later.getId().toString());
    } finally {
      auth.deleteIdentity(identity);
    }
  }

  private void assertForgedCursorIsInvalid(String sort, Consumer<ObjectNode> forge) {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    for (var title : new String[] {"Alpha", "Bravo"}) {
      movies.saveAndFlush(
          Movie.builder()
              .title(title)
              .titleSort(title)
              .runtime(90)
              .releaseDate(LocalDate.of(2020, 1, 1))
              .library(library)
              .build());
    }

    var identity = auth.createIdentity();
    var bearer = auth.profileBearer(identity);
    try (var http =
        new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)))) {
      var query =
          """
          { library(id: "%s") { items(%%s, sort: {by: %s, direction: ASC}) {
            edges { cursor }
          } } }
          """
              .formatted(library.getId(), sort);
      var first = http.execute(bearer, query.formatted("first: 1"));
      assertThat(first.has("errors")).as(first.toPrettyString()).isFalse();
      var cursor = first.at("/data/library/items/edges/0/cursor").asString();
      var genuine =
          http.execute(bearer, query.formatted("first: 1, after: \"%s\"".formatted(cursor)));
      assertThat(genuine.has("errors")).as(genuine.toPrettyString()).isFalse();

      var forged =
          http.execute(
              bearer, query.formatted("first: 1, after: \"%s\"".formatted(forge(cursor, forge))));

      assertThat(forged.at("/errors/0/extensions/code").asString())
          .as(forged.toPrettyString())
          .isEqualTo("INVALID_CURSOR");
    } finally {
      auth.deleteIdentity(identity);
    }
  }

  private static String forge(String cursor, Consumer<ObjectNode> change) {
    var node =
        (ObjectNode)
            JSON.readTree(
                new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
    change.accept(node);
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(JSON.writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
  }

  private static ObjectNode mediaFilter(ObjectNode cursor) {
    return (ObjectNode) cursor.get("mediaFilter");
  }
}
