package com.streamarr.server.services;

import static com.streamarr.server.support.HeldCreditInsert.CreditTable.COMPANY;
import static com.streamarr.server.support.HeldCreditInsert.CreditTable.GENRE;
import static com.streamarr.server.support.HeldCreditInsert.CreditTable.PERSON;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.ArtworkRequestRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.support.HeldCreditInsert;
import com.streamarr.server.support.HeldCreditInsert.CreditTable;
import com.streamarr.server.support.OrderedSourceIds;
import com.streamarr.server.support.TestFixtureWork;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Saves one media title's credits while another media title's open transaction inserts the same new
 * credits in a conflicting order. The other media title inserts its first credit, waits until this
 * save waits on it, then inserts its second credit and commits.
 */
@Tag("IntegrationTest")
@DisplayName("Credit Service Concurrency Integration Tests")
class CreditServiceConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private CreditService creditService;
  @Autowired private DataSource dataSource;
  @Autowired private PersonRepository personRepository;
  @Autowired private GenreRepository genreRepository;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private ArtworkRequestRepository artworkRequests;

  private OrderedSourceIds sourceIds;

  @BeforeEach
  void setUp() {
    sourceIds = OrderedSourceIds.unique();
  }

  @Test
  @DisplayName(
      "Should save genres when another media title is inserting the same new genres in opposite"
          + " order")
  void shouldSaveGenresWhenAnotherMediaTitleIsInsertingTheSameNewGenresInOppositeOrder()
      throws Exception {
    var genres = new LinkedHashSet<Genre>();
    genres.add(Genre.builder().sourceId(sourceIds.higher()).name("Higher Genre").build());
    genres.add(Genre.builder().sourceId(sourceIds.lower()).name("Lower Genre").build());

    var saved =
        saveWhileAnotherMediaTitleInserts(
            GENRE, MediaTitleCredits.builder().genres(genres).build());

    assertThat(saved.genres())
        .extracting(Genre::getSourceId)
        .containsExactlyInAnyOrder(sourceIds.lower(), sourceIds.higher());
  }

  @Test
  @DisplayName(
      "Should save studios when another media title is inserting the same new studios in opposite"
          + " order")
  void shouldSaveStudiosWhenAnotherMediaTitleIsInsertingTheSameNewStudiosInOppositeOrder()
      throws Exception {
    var studios = new LinkedHashSet<Company>();
    studios.add(Company.builder().sourceId(sourceIds.higher()).name("Higher Studio").build());
    studios.add(Company.builder().sourceId(sourceIds.lower()).name("Lower Studio").build());

    var saved =
        saveWhileAnotherMediaTitleInserts(
            COMPANY, MediaTitleCredits.builder().studios(studios).build());

    assertThat(saved.studios())
        .extracting(Company::getSourceId)
        .containsExactlyInAnyOrder(sourceIds.lower(), sourceIds.higher());
  }

  @Test
  @DisplayName(
      "Should save people when a person is cast on one media title and director of the other media"
          + " title")
  void shouldSavePeopleWhenAPersonIsCastOnOneMediaTitleAndDirectorOfTheOtherMediaTitle()
      throws Exception {
    var credits =
        MediaTitleCredits.builder()
            .cast(
                List.of(Person.builder().sourceId(sourceIds.higher()).name("Higher Actor").build()))
            .directors(
                List.of(
                    Person.builder().sourceId(sourceIds.lower()).name("Lower Director").build()))
            .build();

    var saved = saveWhileAnotherMediaTitleInserts(PERSON, credits);

    assertThat(saved.cast()).extracting(Person::getSourceId).containsExactly(sourceIds.higher());
    assertThat(saved.directors())
        .extracting(Person::getSourceId)
        .containsExactly(sourceIds.lower());
  }

  @Test
  @DisplayName(
      "Should save credits when another media title inserts a new person and then a new genre")
  void shouldSaveCreditsWhenAnotherMediaTitleInsertsANewPersonAndThenANewGenre() throws Exception {
    var credits =
        MediaTitleCredits.builder()
            .cast(List.of(Person.builder().sourceId(sourceIds.lower()).name("Actor").build()))
            .genres(Set.of(Genre.builder().sourceId(sourceIds.lower()).name("Genre").build()))
            .build();

    var saved = saveWhileAnotherMediaTitleInserts(PERSON, GENRE, credits);

    assertThat(saved.cast()).extracting(Person::getSourceId).containsExactly(sourceIds.lower());
    assertThat(saved.genres()).extracting(Genre::getSourceId).containsExactly(sourceIds.lower());
  }

  @Test
  @DisplayName(
      "Should save credits when another media title inserts a new genre and then a new studio")
  void shouldSaveCreditsWhenAnotherMediaTitleInsertsANewGenreAndThenANewStudio() throws Exception {
    var credits =
        MediaTitleCredits.builder()
            .genres(Set.of(Genre.builder().sourceId(sourceIds.lower()).name("Genre").build()))
            .studios(Set.of(Company.builder().sourceId(sourceIds.lower()).name("Studio").build()))
            .build();

    var saved = saveWhileAnotherMediaTitleInserts(GENRE, COMPANY, credits);

    assertThat(saved.genres()).extracting(Genre::getSourceId).containsExactly(sourceIds.lower());
    assertThat(saved.studios()).extracting(Company::getSourceId).containsExactly(sourceIds.lower());
  }

  @ParameterizedTest
  @EnumSource(CreditTable.class)
  @DisplayName(
      "Should save credits when another media title renamed the same credit and is inserting the"
          + " same new credit")
  void shouldSaveCreditsWhenAnotherMediaTitleRenamedTheSameCreditAndIsInsertingTheSameNewCredit(
      CreditTable table) throws Exception {
    saveExistingCredit(table, sourceIds.lower());
    var credits = renamedThenNewCredits(table);

    try (var otherMediaTitle = HeldCreditInsert.open(dataSource)) {
      otherMediaTitle.lockForRename(table, sourceIds.lower());

      var saved =
          otherMediaTitle.commitAfterSaveWaits(
              () -> creditService.getOrCreateCredits(requestFor(credits)),
              held -> held.insert(table, sourceIds.higher()));

      assertThat(savedNamesBySourceId(table, saved))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(sourceIds.lower(), "Renamed", sourceIds.higher(), "New"));
    }
  }

  @Test
  @DisplayName(
      "Should save every media title's credits and one artwork request per credit when many media"
          + " titles list the same people and studio at once")
  void shouldSaveEveryMediaTitlesCreditsAndOneArtworkRequestPerCreditWhenListedAtOnce()
      throws Exception {
    var credits =
        MediaTitleCredits.builder()
            .cast(
                List.of(
                    Person.builder().sourceId(sourceIds.higher()).name("Higher Actor").build(),
                    Person.builder().sourceId(sourceIds.lower()).name("Lower Actor").build()))
            .studios(Set.of(Company.builder().sourceId(sourceIds.lower()).name("Studio").build()))
            .build();
    var request =
        MediaTitleCreditsRequest.builder()
            .credits(credits)
            .personImageSources(
                Map.of(
                    sourceIds.higher(), List.of(profile("/higher.jpg")),
                    sourceIds.lower(), List.of(profile("/lower.jpg"))))
            .companyImageSources(
                Map.of(
                    sourceIds.lower(),
                    List.<ImageSource>of(new TmdbImageSource(ImageType.LOGO, "/studio.png"))))
            .imageRefreshMode(ImageRefreshMode.PRESERVE)
            .build();
    var mediaTitles = 8;
    var start = new CountDownLatch(1);

    var saved = new ArrayList<Future<MediaTitleCredits>>();
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      for (var mediaTitle = 0; mediaTitle < mediaTitles; mediaTitle++) {
        saved.add(
            executor.submit(
                () -> {
                  start.await();
                  return creditService.getOrCreateCredits(request);
                }));
      }

      start.countDown();
      for (var credit : saved) {
        assertThat(credit.get(30, TimeUnit.SECONDS).cast()).hasSize(2);
      }
    }

    var people = saved.getFirst().resultNow().cast().stream().map(Person::getId).toList();
    var studio = saved.getFirst().resultNow().studios().iterator().next().getId();
    assertThat(
            artworkRequests.findArtworkStates(
                List.of(
                    new ArtworkItem(people.get(0), ImageEntityType.PERSON),
                    new ArtworkItem(people.get(1), ImageEntityType.PERSON),
                    new ArtworkItem(studio, ImageEntityType.COMPANY))))
        .hasSize(3)
        .allMatch(
            state -> state.pendingGeneration().isPresent() || !state.artworkResults().isEmpty());
  }

  private static ImageSource profile(String key) {
    return new TmdbImageSource(ImageType.PROFILE, key);
  }

  private MediaTitleCredits saveWhileAnotherMediaTitleInserts(
      CreditTable firstTable, CreditTable secondTable, MediaTitleCredits credits)
      throws SQLException {
    try (var otherMediaTitle = HeldCreditInsert.open(dataSource)) {
      otherMediaTitle.insert(firstTable, sourceIds.lower());

      return otherMediaTitle.commitAfterSaveWaits(
          () -> creditService.getOrCreateCredits(requestFor(credits)),
          held -> held.insert(secondTable, sourceIds.lower()));
    }
  }

  private MediaTitleCredits saveWhileAnotherMediaTitleInserts(
      CreditTable table, MediaTitleCredits credits) throws SQLException {
    try (var otherMediaTitle = HeldCreditInsert.open(dataSource)) {
      otherMediaTitle.insert(table, sourceIds.lower());

      return otherMediaTitle.commitAfterSaveWaits(
          () -> creditService.getOrCreateCredits(requestFor(credits)),
          held -> held.insert(table, sourceIds.higher()));
    }
  }

  private static MediaTitleCreditsRequest requestFor(MediaTitleCredits credits) {
    return MediaTitleCreditsRequest.builder()
        .credits(credits)
        .imageRefreshMode(ImageRefreshMode.PRESERVE)
        .build();
  }

  private void saveExistingCredit(CreditTable table, String sourceId) {
    switch (table) {
      case PERSON ->
          personRepository.saveAndFlush(
              Person.builder().sourceId(sourceId).name("Earlier Name").build());
      case GENRE ->
          genreRepository.saveAndFlush(
              Genre.builder().sourceId(sourceId).name("Earlier Name").build());
      case COMPANY ->
          companyRepository.saveAndFlush(
              Company.builder().sourceId(sourceId).name("Earlier Name").build());
    }
  }

  private MediaTitleCredits renamedThenNewCredits(CreditTable table) {
    return switch (table) {
      case PERSON ->
          MediaTitleCredits.builder()
              .cast(
                  List.of(
                      Person.builder().sourceId(sourceIds.lower()).name("Renamed").build(),
                      Person.builder().sourceId(sourceIds.higher()).name("New").build()))
              .build();
      case GENRE ->
          MediaTitleCredits.builder()
              .genres(
                  Set.of(
                      Genre.builder().sourceId(sourceIds.lower()).name("Renamed").build(),
                      Genre.builder().sourceId(sourceIds.higher()).name("New").build()))
              .build();
      case COMPANY ->
          MediaTitleCredits.builder()
              .studios(
                  Set.of(
                      Company.builder().sourceId(sourceIds.lower()).name("Renamed").build(),
                      Company.builder().sourceId(sourceIds.higher()).name("New").build()))
              .build();
    };
  }

  private static Map<String, String> savedNamesBySourceId(
      CreditTable table, MediaTitleCredits saved) {
    return switch (table) {
      case PERSON ->
          saved.cast().stream().collect(Collectors.toMap(Person::getSourceId, Person::getName));
      case GENRE ->
          saved.genres().stream().collect(Collectors.toMap(Genre::getSourceId, Genre::getName));
      case COMPANY ->
          saved.studios().stream()
              .collect(Collectors.toMap(Company::getSourceId, Company::getName));
    };
  }
}
