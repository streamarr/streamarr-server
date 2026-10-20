package com.streamarr.server;

import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.jooq.SortOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// Failsafe runs this class in its own JVM whose default zone is Pacific/Apia, which skipped
// 2011-12-30 when it moved across the date line.
@Tag("IntegrationTest")
@Tag("SkippedMidnightZoneTest")
@DisplayName("Date and time persistence when the JVM zone skipped a whole day")
class SkippedMidnightZoneIT extends AbstractIntegrationTest {

  private static final LocalDate SKIPPED_DAY = LocalDate.of(2011, 12, 30);
  private static final String PRE_GREGORIAN_INSTANT = "1500-03-01T00:00:00Z";
  private static final String BEFORE_COMMON_ERA_INSTANT = "-0043-03-15T00:00:00Z";
  private static final String PRE_EPOCH_FRACTION = "1969-12-31T23:59:59.500Z";

  @Autowired private MovieService movieService;
  @Autowired private MovieRepository movieRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  private Library library;

  @BeforeEach
  void setUp() {
    assertThat(ZoneId.systemDefault().getRules().getValidOffsets(SKIPPED_DAY.atStartOfDay()))
        .as("the JVM default zone must skip %s", SKIPPED_DAY)
        .isEmpty();

    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
  }

  @Test
  @DisplayName("Should list a movie with its release date when the JVM zone skipped that day")
  void shouldListMovieWithItsReleaseDateWhenJvmZoneSkippedThatDay() {
    saveMovieReleasedOn(SKIPPED_DAY);
    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.ASC)
            .build();

    var page = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

    assertThat(page.items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.item().getReleaseDate()).isEqualTo(SKIPPED_DAY);
              assertThat(item.sortValue()).isEqualTo(SKIPPED_DAY);
            });
  }

  @Test
  @DisplayName("Should store a release date unchanged when the JVM zone skipped that day")
  void shouldStoreReleaseDateUnchangedWhenJvmZoneSkippedThatDay() {
    var movie = saveMovieReleasedOn(SKIPPED_DAY);

    var stored =
        jdbcTemplate.queryForObject(
            "select release_date::text from movie where id = ?", String.class, movie.getId());

    assertThat(stored).isEqualTo("2011-12-30");
  }

  @Test
  @DisplayName("Should read a release date unchanged when the JVM zone skipped that day")
  void shouldReadReleaseDateUnchangedWhenJvmZoneSkippedThatDay() {
    var movie = saveMovieReleasedOn(null);
    transactionTemplate.executeWithoutResult(
        _ ->
            jdbcTemplate.update(
                "update movie set release_date = date '2011-12-30' where id = ?", movie.getId()));

    var found = movieRepository.findById(movie.getId()).orElseThrow();

    assertThat(found.getReleaseDate()).isEqualTo(SKIPPED_DAY);
  }

  @ParameterizedTest
  @ValueSource(strings = {PRE_GREGORIAN_INSTANT, BEFORE_COMMON_ERA_INSTANT, PRE_EPOCH_FRACTION})
  @DisplayName("Should store an instant unchanged when it precedes the Unix epoch")
  void shouldStoreInstantUnchangedWhenItPrecedesUnixEpoch(String value) {
    var instant = Instant.parse(value);

    var saved =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.buildFakeLibrary().toBuilder().scanStartedOn(instant).build());

    var storedEpochSeconds =
        jdbcTemplate.queryForObject(
            "select extract(epoch from scan_started_on) from library where id = ?",
            BigDecimal.class,
            saved.getId());
    assertThat(storedEpochSeconds).isEqualByComparingTo(epochSeconds(instant));
  }

  @ParameterizedTest
  @ValueSource(strings = {PRE_GREGORIAN_INSTANT, BEFORE_COMMON_ERA_INSTANT, PRE_EPOCH_FRACTION})
  @DisplayName("Should read an instant unchanged when it precedes the Unix epoch")
  void shouldReadInstantUnchangedWhenItPrecedesUnixEpoch(String value) {
    var instant = Instant.parse(value);
    transactionTemplate.executeWithoutResult(
        _ ->
            jdbcTemplate.update(
                "update library set scan_started_on = to_timestamp(?) where id = ?",
                epochSeconds(instant),
                library.getId()));

    var found = libraryRepository.findById(library.getId()).orElseThrow();

    assertThat(found.getScanStartedOn()).isEqualTo(instant);
  }

  private static BigDecimal epochSeconds(Instant instant) {
    return BigDecimal.valueOf(instant.getEpochSecond())
        .add(BigDecimal.valueOf(instant.getNano(), 9));
  }

  private Movie saveMovieReleasedOn(LocalDate releaseDate) {
    return movieRepository.saveAndFlush(
        Movie.builder()
            .title("Skipped Day")
            .titleSort("skipped day")
            .releaseDate(releaseDate)
            .library(library)
            .build());
  }
}
