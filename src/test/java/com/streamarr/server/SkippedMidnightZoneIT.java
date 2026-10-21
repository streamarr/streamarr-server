package com.streamarr.server;

import static com.streamarr.server.fixtures.PaginationFixture.firstRows;
import static com.streamarr.server.fixtures.PaginationFixture.rowsAfter;
import static com.streamarr.server.fixtures.PaginationFixture.rowsBefore;
import static com.streamarr.server.jooq.generated.tables.Library.LIBRARY;
import static com.streamarr.server.jooq.generated.tables.Movie.MOVIE;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.impl.DSL;
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
  @Autowired private EntityManager entityManager;

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
    var filter = releaseDateFilter();

    var page = movieService.getMoviesWithFilter(filter, firstRows(10));

    assertThat(page.rows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.item().getReleaseDate()).isEqualTo(SKIPPED_DAY);
              assertThat(row.position().value()).isEqualTo(new SortValue.OfDate(SKIPPED_DAY));
            });
  }

  @Test
  @DisplayName("Should page past every movie released on a day the JVM zone skipped")
  void shouldPagePastEveryMovieReleasedOnDayJvmZoneSkipped() {
    saveMovieTitledReleasedOn("Skipped Day A", SKIPPED_DAY);
    saveMovieTitledReleasedOn("Skipped Day B", SKIPPED_DAY);
    saveMovieTitledReleasedOn("Day After", SKIPPED_DAY.plusDays(1));
    var filter = releaseDateFilter();

    var first = movieService.getMoviesWithFilter(filter, firstRows(1));
    var second = movieService.getMoviesWithFilter(filter, rowsAfter(1, first.rows().getFirst()));
    var third = movieService.getMoviesWithFilter(filter, rowsAfter(1, second.rows().getFirst()));
    var beforeLast =
        movieService.getMoviesWithFilter(filter, rowsBefore(2, third.rows().getFirst()));

    assertThat(Stream.of(first, second, third).flatMap(page -> page.rows().stream()))
        .extracting(row -> row.item().getTitle())
        .containsExactlyInAnyOrder("Skipped Day A", "Skipped Day B", "Day After")
        .endsWith("Day After");
    assertThat(beforeLast.rows())
        .extracting(row -> row.item().getTitle())
        .containsExactlyInAnyOrder("Skipped Day A", "Skipped Day B");
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
    storeScanStartedOn(instant);

    var found = libraryRepository.findById(library.getId()).orElseThrow();

    assertThat(found.getScanStartedOn()).isEqualTo(instant);
  }

  @Test
  @DisplayName("Should match a release date when a native query binds a day the JVM zone skipped")
  void shouldMatchReleaseDateWhenNativeQueryBindsDayJvmZoneSkipped() {
    var movie = saveMovieReleasedOn(SKIPPED_DAY);

    var ids =
        JooqQueryHelper.nativeQuery(
            entityManager,
            DSL.select(MOVIE.ID)
                .from(MOVIE)
                .where(MOVIE.ID.eq(movie.getId()))
                .and(MOVIE.RELEASE_DATE.eq(SKIPPED_DAY)),
            UUID.class);

    assertThat(ids).containsExactly(movie.getId());
  }

  @ParameterizedTest
  @ValueSource(strings = {PRE_GREGORIAN_INSTANT, BEFORE_COMMON_ERA_INSTANT, PRE_EPOCH_FRACTION})
  @DisplayName("Should match an instant when a native query binds one before the Unix epoch")
  void shouldMatchInstantWhenNativeQueryBindsOneBeforeUnixEpoch(String value) {
    var instant = Instant.parse(value);
    storeScanStartedOn(instant);

    var ids =
        JooqQueryHelper.nativeQuery(
            entityManager,
            DSL.select(LIBRARY.ID)
                .from(LIBRARY)
                .where(LIBRARY.ID.eq(library.getId()))
                .and(LIBRARY.SCAN_STARTED_ON.eq(instant.atOffset(ZoneOffset.UTC))),
            UUID.class);

    assertThat(ids).containsExactly(library.getId());
  }

  @ParameterizedTest
  @ValueSource(strings = {PRE_GREGORIAN_INSTANT, BEFORE_COMMON_ERA_INSTANT, PRE_EPOCH_FRACTION})
  @DisplayName("Should read a row's instant position unchanged when it precedes the Unix epoch")
  void shouldReadRowInstantPositionUnchangedWhenItPrecedesUnixEpoch(String value) {
    var instant = Instant.parse(value);
    storeScanStartedOn(instant);
    var keyset =
        Keyset.<OffsetDateTime>builder()
            .sortValue(LIBRARY.SCAN_STARTED_ON)
            .itemId(LIBRARY.ID)
            .build();

    var rows =
        JooqQueryHelper.nativeRows(
            entityManager,
            DSL.select(LIBRARY.asterisk(), keyset.selectedSortValue())
                .from(LIBRARY)
                .where(LIBRARY.ID.eq(library.getId())),
            Library.class,
            keyset);

    assertThat(rows)
        .singleElement()
        .extracting(row -> row.position().value())
        .isEqualTo(new SortValue.OfInstant(instant));
  }

  private void storeScanStartedOn(Instant instant) {
    transactionTemplate.executeWithoutResult(
        _ ->
            jdbcTemplate.update(
                "update library set scan_started_on = to_timestamp(?) where id = ?",
                epochSeconds(instant),
                library.getId()));
  }

  private static BigDecimal epochSeconds(Instant instant) {
    return BigDecimal.valueOf(instant.getEpochSecond())
        .add(BigDecimal.valueOf(instant.getNano(), 9));
  }

  private MediaFilter releaseDateFilter() {
    return MediaFilter.builder()
        .libraryId(library.getId())
        .sortBy(OrderMediaBy.RELEASE_DATE)
        .sortDirection(SortDirection.ASC)
        .build();
  }

  private Movie saveMovieReleasedOn(LocalDate releaseDate) {
    return saveMovieTitledReleasedOn("Skipped Day", releaseDate);
  }

  private Movie saveMovieTitledReleasedOn(String title, LocalDate releaseDate) {
    return movieRepository.saveAndFlush(
        Movie.builder()
            .title(title)
            .titleSort(title.toLowerCase(Locale.ROOT))
            .releaseDate(releaseDate)
            .library(library)
            .build());
  }
}
