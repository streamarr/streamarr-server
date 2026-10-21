package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.support.AuditColumnFixtures;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("IntegrationTest")
@DisplayName("Library items cursors")
class LibraryItemsCursorIT extends AbstractIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort private int port;
  @Autowired private AuthTestSupport auth;
  @Autowired private LibraryRepository libraries;
  @Autowired private MovieRepository movies;
  @Autowired private AuditColumnFixtures auditColumns;

  @ParameterizedTest(name = "{0} cursor holding {1} {2}")
  @CsvSource({
    "LAST_WATCHED, INSTANT, yesterday",
    "LAST_WATCHED, INSTANT, +294277-01-01T00:00:00Z",
    "ADDED, INSTANT, yesterday",
    "ADDED, INSTANT, -0001-01-01T00:00:00Z",
    "ADDED, INSTANT, 1582-12-31T23:59:59Z",
    "LAST_WATCHED, INSTANT, 0000-12-31T23:59:59Z",
    "RELEASE_DATE, DATE, someday",
    "RELEASE_DATE, DATE, +5874898-01-01",
    "RELEASE_DATE, DATE, 0000-12-31",
    "RELEASE_DATE, DATE, 1582-10-15",
    "RUNTIME, NUMBER, ninety"
  })
  @DisplayName("Should reject a cursor as INVALID_CURSOR when its sort value cannot be read")
  void shouldRejectACursorAsInvalidWhenItsSortValueCannotBeRead(
      String sort, String type, String value) {
    assertForgedCursorIsInvalid(sort, cursor -> cursor.put("type", type).put("value", value));
  }

  @ParameterizedTest(name = "{0} cursor holding {1}")
  @CsvSource({
    "ADDED, TEXT, alpha",
    "ADDED, ABSENT,",
    "RELEASE_DATE, INSTANT, 2020-01-01T00:00:00Z",
    "RUNTIME, DATE, 2020-01-01",
    "TITLE, NUMBER, 90"
  })
  @DisplayName("Should reject a cursor as INVALID_CURSOR when its value is not of the sort's kind")
  void shouldRejectACursorAsInvalidWhenItsValueIsNotOfTheSortsKind(
      String sort, String type, String value) {
    assertForgedCursorIsInvalid(sort, cursor -> cursor.put("type", type).put("value", value));
  }

  @Test
  @DisplayName("Should reject a cursor as INVALID_CURSOR when it was issued under another sort")
  void shouldRejectACursorAsInvalidWhenItWasIssuedUnderAnotherSort() {
    var library = libraryOf("Alpha", "Bravo");

    readItems(
        library,
        http -> {
          var cursor = http.cursorAt(http.items("first: 1, sort: {by: TITLE, direction: ASC}"), 0);

          var reused =
              http.items(
                  "first: 1, after: \"%s\", sort: {by: TITLE, direction: DESC}".formatted(cursor));

          assertThat(reused.at("/errors/0/extensions/code").asString())
              .as(reused.toPrettyString())
              .isEqualTo("INVALID_CURSOR");
        });
  }

  @Test
  @DisplayName("Should continue a title cursor when a later request drops the start letter")
  void shouldContinueATitleCursorWhenALaterRequestDropsTheStartLetter() {
    var library = libraryOf("Alpha", "Bravo", "Charlie");

    readItems(
        library,
        http -> {
          var landing = http.items("first: 1, filter: {startLetter: B}");
          var cursor = http.cursorAt(landing, 0);

          var next = http.items("first: 1, after: \"%s\"".formatted(cursor));
          var previous = http.items("last: 1, before: \"%s\"".formatted(cursor));

          assertThat(http.titles(landing)).containsExactly("Bravo");
          assertThat(http.titles(next)).as(next.toPrettyString()).containsExactly("Charlie");
          assertThat(http.titles(previous)).as(previous.toPrettyString()).containsExactly("Alpha");
        });
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "RELEASE_DATE, ASC, 'Dated 2020,Dated 2021,Aardvark,Aardvark'",
    "RELEASE_DATE, DESC, 'Dated 2021,Dated 2020,Aardvark,Aardvark'",
    "TITLE, ASC, 'Dated 2020,Dated 2021,Aardvark,Aardvark'",
    "TITLE, DESC, 'Aardvark,Aardvark,Dated 2021,Dated 2020'"
  })
  @DisplayName("Should visit every item once when some items have no sort value")
  void shouldVisitEveryItemOnceWhenSomeItemsHaveNoSortValue(
      String sort, String direction, String expectedTitles) {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    saveMovie(library, "Dated 2020", LocalDate.of(2020, 1, 1));
    saveMovie(library, "Dated 2021", LocalDate.of(2021, 1, 1));
    for (var i = 0; i < 2; i++) {
      movies.saveAndFlush(Movie.builder().title("Aardvark").library(library).build());
    }

    readItems(
        library,
        http -> {
          var order = "sort: {by: %s, direction: %s}".formatted(sort, direction);
          var whole = http.items("first: 10, " + order);
          var visited = new ArrayList<String>();
          var page = http.items("first: 1, " + order);
          while (!page.at("/data/library/items/edges").isEmpty()) {
            visited.add(page.at("/data/library/items/edges/0/node/id").asString());
            page =
                http.items("first: 1, after: \"%s\", %s".formatted(http.cursorAt(page, 0), order));
          }

          assertThat(http.titles(whole))
              .containsExactlyElementsOf(List.of(expectedTitles.split(",")));
          assertThat(visited).as("one-item pages").containsExactlyElementsOf(http.ids(whole));
        });
  }

  @Test
  @DisplayName("Should follow a RELEASE_DATE cursor when its item is released after year 9999")
  void shouldFollowAReleaseDateCursorWhenItsItemIsReleasedAfterYear9999() {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var later = saveMovie(library, "Bravo", LocalDate.of(10001, 1, 1));
    saveMovie(library, "Alpha", LocalDate.of(10000, 1, 1));

    readItems(
        library,
        http -> {
          var order = "sort: {by: RELEASE_DATE, direction: ASC}";
          var cursor = http.cursorAt(http.items("first: 1, " + order), 0);

          var next = http.items("first: 1, after: \"%s\", %s".formatted(cursor, order));

          assertThat(next.has("errors")).as(next.toPrettyString()).isFalse();
          assertThat(http.ids(next)).containsExactly(later.getId().toString());
        });
  }

  @ParameterizedTest(name = "added at {0} and {1}")
  @CsvSource({
    "1583-01-01T00:00:00Z, 1583-01-02T00:00:00Z",
    "1969-12-31T23:59:59.250Z, 1969-12-31T23:59:59.750Z",
    "+294276-12-30T00:00:00Z, +294276-12-31T23:59:59.999999Z"
  })
  @DisplayName(
      "Should follow an ADDED cursor to the next item when items were added at any stored time")
  void shouldFollowAnAddedCursorToTheNextItemWhenItemsWereAddedAtAnyStoredTime(
      String earlier, String later) {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var first = movies.saveAndFlush(Movie.builder().title("Alpha").library(library).build());
    var next = movies.saveAndFlush(Movie.builder().title("Bravo").library(library).build());
    auditColumns.overwriteCreatedOn(
        BASE_COLLECTABLE, BASE_COLLECTABLE.ID.eq(first.getId()), Instant.parse(earlier));
    auditColumns.overwriteCreatedOn(
        BASE_COLLECTABLE, BASE_COLLECTABLE.ID.eq(next.getId()), Instant.parse(later));

    readItems(
        library,
        http -> {
          var order = "sort: {by: ADDED, direction: ASC}";
          var page = http.items("first: 1, " + order);

          var following =
              http.items("first: 1, after: \"%s\", %s".formatted(http.cursorAt(page, 0), order));

          assertThat(http.ids(page)).containsExactly(first.getId().toString());
          assertThat(http.ids(following))
              .as(following.toPrettyString())
              .containsExactly(next.getId().toString());
        });
  }

  private void assertForgedCursorIsInvalid(String sort, Consumer<ObjectNode> forge) {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    for (var title : List.of("Alpha", "Bravo")) {
      movies.saveAndFlush(
          Movie.builder()
              .title(title)
              .titleSort(title)
              .runtime(90)
              .releaseDate(LocalDate.of(2020, 1, 1))
              .library(library)
              .build());
    }

    readItems(
        library,
        http -> {
          var order = "sort: {by: %s, direction: ASC}".formatted(sort);
          var first = http.items("first: 1, " + order);
          assertThat(first.has("errors")).as(first.toPrettyString()).isFalse();
          var cursor = http.cursorAt(first, 0);
          var genuine = http.items("first: 1, after: \"%s\", %s".formatted(cursor, order));
          assertThat(genuine.has("errors")).as(genuine.toPrettyString()).isFalse();

          var forged =
              http.items("first: 1, after: \"%s\", %s".formatted(forge(cursor, forge), order));

          assertThat(forged.at("/errors/0/extensions/code").asString())
              .as(forged.toPrettyString())
              .isEqualTo("INVALID_CURSOR");
        });
  }

  private Library libraryOf(String... titles) {
    var library = libraries.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    for (var title : titles) {
      movies.saveAndFlush(Movie.builder().title(title).titleSort(title).library(library).build());
    }

    return library;
  }

  private Movie saveMovie(Library library, String title, LocalDate released) {
    return movies.saveAndFlush(
        Movie.builder()
            .title(title)
            .titleSort(title)
            .releaseDate(released)
            .library(library)
            .build());
  }

  private void readItems(Library library, Consumer<LibraryItems> reads) {
    var identity = auth.createIdentity();
    try (var http =
        new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)))) {
      reads.accept(new LibraryItems(http, auth.profileBearer(identity), library));
    } finally {
      auth.deleteIdentity(identity);
    }
  }

  private record LibraryItems(GraphQlHttpTestClient http, String bearer, Library library) {

    JsonNode items(String arguments) {
      return http.execute(
          bearer,
          """
          { library(id: "%s") { items(%s) {
            edges { cursor node { ... on Movie { id title } } }
          } } }
          """
              .formatted(library.getId(), arguments));
    }

    String cursorAt(JsonNode page, int index) {
      return page.at("/data/library/items/edges/%d/cursor".formatted(index)).asString();
    }

    List<String> titles(JsonNode page) {
      return nodeValues(page, node -> node.path("title").asString());
    }

    List<String> ids(JsonNode page) {
      return nodeValues(page, node -> node.path("id").asString());
    }

    private static List<String> nodeValues(JsonNode page, Function<JsonNode, String> value) {
      var values = new ArrayList<String>();
      page.at("/data/library/items/edges")
          .forEach(edge -> values.add(value.apply(edge.path("node"))));
      return values;
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
}
