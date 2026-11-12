package com.streamarr.server.services.notification;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static com.streamarr.server.support.SpecifiedOnlyNotificationKinds.SCAN_OR_REFRESH_FINISHED;
import static com.streamarr.server.support.SpecifiedOnlyNotificationKinds.UNMATCHED_ITEMS;
import static com.streamarr.server.support.StoredNotificationRows.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.events.notification.DismissNotificationsEvent;
import com.streamarr.server.services.events.notification.DismissalSelector;
import com.streamarr.server.services.events.notification.NotificationKind;
import com.streamarr.server.services.events.notification.NotificationSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.ScanOrRefreshFinishedSnapshot;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.UnmatchedItemsSnapshot;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.StoredNotificationRows.Row;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The kinds that ADR 0063 specifies for later builds, defined only in test code, fit the module's
 * general model: the same writer, noise rule, dismissal and retention handle them, and a kind may
 * add its figures to the unread news it updates. The tests tell them as their domains will, to
 * made-up ServerAdmins about a made-up library.
 */
@Tag("IntegrationTest")
@DisplayName("Specified Only Kinds Fit Integration Tests")
class SpecifiedOnlyKindsFitIT extends AbstractIntegrationTest {

  private static final Instant FIRST_SCAN =
      Instant.now().truncatedTo(ChronoUnit.MICROS).minus(Duration.ofHours(2));
  private static final Instant LATER_SCAN = FIRST_SCAN.plus(Duration.ofHours(1));

  @Autowired private NotificationRetentionSweeper sweeper;
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private final RecipientReference ava = RecipientReference.account(UUID.randomUUID());
  private final RecipientReference ben = RecipientReference.account(UUID.randomUUID());
  private final SubjectReference movies = SpecifiedOnlyNotificationKinds.library(UUID.randomUUID());

  private StoredNotificationRows rows;

  @BeforeEach
  void setUp() {
    rows = new StoredNotificationRows(dsl);
  }

  @AfterEach
  void tearDown() {
    rows.deleteOf(Set.of(ava, ben));
  }

  @Test
  @DisplayName(
      "Should add the figures to the unread news, and take the later name and time, when a later"
          + " scan of the library finishes")
  void shouldAddTheFiguresToTheUnreadNewsWhenALaterScanOfTheLibraryFinishes() {
    tell(
        SCAN_OR_REFRESH_FINISHED, new ScanOrRefreshFinishedSnapshot("Movies", 3, 1, 0), FIRST_SCAN);
    var made = rows.of(ava).getFirst();

    tell(SCAN_OR_REFRESH_FINISHED, new ScanOrRefreshFinishedSnapshot("Films", 2, 0, 4), LATER_SCAN);

    assertThat(rows.of(ava))
        .singleElement()
        .satisfies(
            updated -> {
              assertThat(updated.id()).isEqualTo(made.id());
              assertThat(updated.snapshot())
                  .isEqualTo(document(new ScanOrRefreshFinishedSnapshot("Films", 5, 1, 4)));
              assertThat(updated.latestEventAt()).isEqualTo(LATER_SCAN);
              assertThat(updated.readAt()).isEmpty();
            });
  }

  @Test
  @DisplayName(
      "Should add the figures and keep the later name and time when an earlier scan's news arrives"
          + " late")
  void shouldAddTheFiguresAndKeepTheLaterNameAndTimeWhenAnEarlierScansNewsArrivesLate() {
    tell(SCAN_OR_REFRESH_FINISHED, new ScanOrRefreshFinishedSnapshot("Films", 2, 0, 4), LATER_SCAN);

    tell(
        SCAN_OR_REFRESH_FINISHED, new ScanOrRefreshFinishedSnapshot("Movies", 3, 1, 0), FIRST_SCAN);

    assertThat(rows.of(ava))
        .singleElement()
        .satisfies(
            updated -> {
              assertThat(updated.snapshot())
                  .isEqualTo(document(new ScanOrRefreshFinishedSnapshot("Films", 5, 1, 4)));
              assertThat(updated.latestEventAt()).isEqualTo(LATER_SCAN);
            });
  }

  @Test
  @DisplayName(
      "Should tell every ServerAdmin, the one that started it included, when a scan finishes")
  void shouldTellEveryServerAdminTheOneThatStartedItIncludedWhenAScanFinishes() {
    var snapshot = new ScanOrRefreshFinishedSnapshot("Movies", 3, 1, 0);

    publish(
        TellNotificationEvent.builder()
            .kind(SCAN_OR_REFRESH_FINISHED)
            .subject(movies)
            .recipients(Set.of(ava, ben))
            .snapshot(snapshot)
            .at(FIRST_SCAN)
            .build());

    assertThat(rows.about(movies)).extracting(Row::recipient).containsExactlyInAnyOrder(ava, ben);
    assertThat(rows.about(movies)).extracting(Row::snapshot).containsOnly(document(snapshot));
  }

  @Test
  @DisplayName("Should keep the two library kinds side by side when both are told about a library")
  void shouldKeepTheTwoLibraryKindsSideBySideWhenBothAreToldAboutALibrary() {
    tell(
        SCAN_OR_REFRESH_FINISHED, new ScanOrRefreshFinishedSnapshot("Movies", 3, 1, 0), FIRST_SCAN);

    tell(UNMATCHED_ITEMS, new UnmatchedItemsSnapshot("Movies", 14), FIRST_SCAN);

    assertThat(rows.of(ava))
        .extracting(Row::kind)
        .containsExactlyInAnyOrder(SCAN_OR_REFRESH_FINISHED.name(), UNMATCHED_ITEMS.name());
  }

  @Test
  @DisplayName(
      "Should make new news with only the later figures when the ServerAdmin read the earlier news"
          + " about the library")
  void shouldMakeNewNewsWhenTheServerAdminReadTheEarlierNewsAboutTheLibrary() {
    var first = new ScanOrRefreshFinishedSnapshot("Movies", 3, 1, 0);
    tell(SCAN_OR_REFRESH_FINISHED, first, FIRST_SCAN);
    markRead(ava);
    var later = new ScanOrRefreshFinishedSnapshot("Movies", 2, 0, 4);

    tell(SCAN_OR_REFRESH_FINISHED, later, LATER_SCAN);

    assertThat(rows.of(ava))
        .extracting(Row::snapshot, row -> row.readAt().isPresent())
        .containsExactlyInAnyOrder(tuple(document(first), true), tuple(document(later), false));
  }

  @Test
  @DisplayName(
      "Should replace the number of unmatched items when a later scan adds unmatched items")
  void shouldReplaceTheNumberWhenALaterScanAddsUnmatchedItems() {
    tell(UNMATCHED_ITEMS, new UnmatchedItemsSnapshot("Movies", 14), FIRST_SCAN);

    tell(UNMATCHED_ITEMS, new UnmatchedItemsSnapshot("Movies", 20), LATER_SCAN);

    assertThat(rows.of(ava))
        .singleElement()
        .satisfies(
            updated -> {
              assertThat(updated.snapshot())
                  .isEqualTo(document(new UnmatchedItemsSnapshot("Movies", 20)));
              assertThat(updated.latestEventAt()).isEqualTo(LATER_SCAN);
            });
  }

  @Test
  @DisplayName(
      "Should dismiss the library's news for every recipient when it is dismissed by subject")
  void shouldDismissTheLibrarysNewsWhenItIsDismissedBySubject() {
    publish(
        TellNotificationEvent.builder()
            .kind(SCAN_OR_REFRESH_FINISHED)
            .subject(movies)
            .recipients(Set.of(ava, ben))
            .snapshot(new ScanOrRefreshFinishedSnapshot("Movies", 3, 1, 0))
            .at(FIRST_SCAN)
            .build());
    tell(UNMATCHED_ITEMS, new UnmatchedItemsSnapshot("Movies", 14), FIRST_SCAN);

    publish(new DismissNotificationsEvent(DismissalSelector.bySubject(Set.of(movies)).build()));

    assertThat(rows.about(movies)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should delete the library's read news when retention runs, and keep its unread news")
  void shouldDeleteTheLibrarysReadNewsWhenRetentionRuns() {
    var pastThePeriod = Instant.now().minus(Duration.ofDays(91));
    tell(
        SCAN_OR_REFRESH_FINISHED,
        new ScanOrRefreshFinishedSnapshot("Movies", 3, 1, 0),
        pastThePeriod);
    markRead(ava);
    tell(UNMATCHED_ITEMS, new UnmatchedItemsSnapshot("Movies", 14), pastThePeriod);

    sweeper.sweep();

    assertThat(rows.of(ava)).extracting(Row::kind).containsExactly(UNMATCHED_ITEMS.name());
  }

  /** Tells Ava of the library at the instant, as the library domain will. */
  private void tell(NotificationKind kind, NotificationSnapshot snapshot, Instant at) {
    publish(
        TellNotificationEvent.builder()
            .kind(kind)
            .subject(movies)
            .recipients(Set.of(ava))
            .snapshot(snapshot)
            .at(at)
            .build());
  }

  private void publish(Object event) {
    transactionTemplate.executeWithoutResult(_ -> eventPublisher.publishEvent(event));
  }

  /** Marks every Notification of the recipient read, as its reader would. */
  private void markRead(RecipientReference recipient) {
    transactionTemplate.executeWithoutResult(
        _ ->
            dsl.update(NOTIFICATION)
                .set(NOTIFICATION.READ_AT, Instant.now().atOffset(ZoneOffset.UTC))
                .where(NOTIFICATION.RECIPIENT_TYPE.eq(recipient.type()))
                .and(NOTIFICATION.RECIPIENT_ID.eq(recipient.id()))
                .execute());
  }
}
