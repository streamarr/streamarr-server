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
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Saves one media title's credits while another title's open transaction inserts the same new
 * credits in a conflicting order. The other title inserts its first credit, waits until this save
 * waits on it, then inserts its second credit and commits.
 */
@Tag("IntegrationTest")
@DisplayName("Credit Service Concurrency Integration Tests")
class CreditServiceConcurrencyIT extends AbstractIntegrationTest {

  private static final Duration SAVE_TIMEOUT = Duration.ofSeconds(15);

  @Autowired private CreditService creditService;
  @Autowired private DataSource dataSource;

  private String lowerSourceId;
  private String higherSourceId;

  @BeforeEach
  void setUp() {
    var suffix = UUID.randomUUID();
    lowerSourceId = "100-" + suffix;
    higherSourceId = "200-" + suffix;
  }

  @Test
  @DisplayName(
      "Should save genres when another title is inserting the same new genres in opposite order")
  void shouldSaveGenresWhenAnotherTitleIsInsertingTheSameNewGenresInOppositeOrder()
      throws Exception {
    var genres = new LinkedHashSet<Genre>();
    genres.add(Genre.builder().sourceId(higherSourceId).name("Higher Genre").build());
    genres.add(Genre.builder().sourceId(lowerSourceId).name("Lower Genre").build());

    var saved =
        saveWhileAnotherTitleInserts(GENRE, MediaTitleCredits.builder().genres(genres).build());

    assertThat(saved.genres())
        .extracting(Genre::getSourceId)
        .containsExactlyInAnyOrder(lowerSourceId, higherSourceId);
  }

  @Test
  @DisplayName(
      "Should save studios when another title is inserting the same new studios in opposite order")
  void shouldSaveStudiosWhenAnotherTitleIsInsertingTheSameNewStudiosInOppositeOrder()
      throws Exception {
    var studios = new LinkedHashSet<Company>();
    studios.add(Company.builder().sourceId(higherSourceId).name("Higher Studio").build());
    studios.add(Company.builder().sourceId(lowerSourceId).name("Lower Studio").build());

    var saved =
        saveWhileAnotherTitleInserts(COMPANY, MediaTitleCredits.builder().studios(studios).build());

    assertThat(saved.studios())
        .extracting(Company::getSourceId)
        .containsExactlyInAnyOrder(lowerSourceId, higherSourceId);
  }

  @Test
  @DisplayName(
      "Should save people when a person is cast on one title and director of the other title")
  void shouldSavePeopleWhenAPersonIsCastOnOneTitleAndDirectorOfTheOtherTitle() throws Exception {
    var credits =
        MediaTitleCredits.builder()
            .cast(List.of(Person.builder().sourceId(higherSourceId).name("Higher Actor").build()))
            .directors(
                List.of(Person.builder().sourceId(lowerSourceId).name("Lower Director").build()))
            .build();

    var saved = saveWhileAnotherTitleInserts(PERSON, credits);

    assertThat(saved.cast()).extracting(Person::getSourceId).containsExactly(higherSourceId);
    assertThat(saved.directors()).extracting(Person::getSourceId).containsExactly(lowerSourceId);
  }

  private MediaTitleCredits saveWhileAnotherTitleInserts(
      CreditTable table, MediaTitleCredits credits) throws SQLException {
    var request =
        MediaTitleCreditsRequest.builder()
            .credits(credits)
            .imageRefreshMode(ImageRefreshMode.PRESERVE)
            .build();

    try (var otherTitle = HeldCreditInsert.open(dataSource);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      otherTitle.insert(table, lowerSourceId);
      var save =
          CompletableFuture.supplyAsync(() -> creditService.getOrCreateCredits(request), executor);

      otherTitle.awaitWaitingTransaction();
      otherTitle.insert(table, higherSourceId);
      otherTitle.commit();

      assertThat(save).succeedsWithin(SAVE_TIMEOUT);
      return save.join();
    }
  }
}
