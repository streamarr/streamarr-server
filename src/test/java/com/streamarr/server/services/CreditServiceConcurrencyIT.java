package com.streamarr.server.services;

import static com.streamarr.server.support.HeldCreditInsert.CreditTable.COMPANY;
import static com.streamarr.server.support.HeldCreditInsert.CreditTable.GENRE;
import static com.streamarr.server.support.HeldCreditInsert.CreditTable.PERSON;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.support.HeldCreditInsert;
import com.streamarr.server.support.HeldCreditInsert.CreditTable;
import com.streamarr.server.support.OrderedSourceIds;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
}
