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
import com.streamarr.server.services.events.notification.RequestResolution;
import com.streamarr.server.services.events.notification.ResolveRequestsEvent;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.InvitationAnswer;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.InvitationReceivedSnapshot;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.ManagementRemovedSnapshot;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.ScanOrRefreshFinishedSnapshot;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.UnmatchedItemsSnapshot;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.StoredNotificationRows.Row;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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
 * general model: the same writer, noise rule, resolution, dismissal and retention handle them, and
 * a kind may add its figures to the unread news it updates. The tests tell them as their domains
 * will, to made-up Accounts about a made-up library, Profile and invitations.
 */
@Tag("IntegrationTest")
@DisplayName("Specified Only Kinds Fit Integration Tests")
class SpecifiedOnlyKindsFitIT extends AbstractIntegrationTest {

  private static final Instant EARLIER =
      Instant.now().truncatedTo(ChronoUnit.MICROS).minus(Duration.ofHours(2));
  private static final Instant LATER = EARLIER.plus(Duration.ofHours(1));

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
    tell(SCAN_OR_REFRESH_FINISHED, new ScanOrRefreshFinishedSnapshot("Movies", 3, 1, 0), EARLIER);
    var made = rows.of(ava).getFirst();

    tell(SCAN_OR_REFRESH_FINISHED, new ScanOrRefreshFinishedSnapshot("Films", 2, 0, 4), LATER);

    assertThat(rows.of(ava))
        .singleElement()
        .satisfies(
            updated -> {
              assertThat(updated.id()).isEqualTo(made.id());
              assertThat(updated.snapshot())
                  .isEqualTo(document(new ScanOrRefreshFinishedSnapshot("Films", 5, 1, 4)));
              assertThat(updated.latestEventAt()).isEqualTo(LATER);
              assertThat(updated.readAt()).isEmpty();
            });
  }

  @Test
  @DisplayName(
      "Should add the figures and keep the later name and time when an earlier scan's news arrives"
          + " late")
  void shouldAddTheFiguresAndKeepTheLaterNameAndTimeWhenAnEarlierScansNewsArrivesLate() {
    tell(SCAN_OR_REFRESH_FINISHED, new ScanOrRefreshFinishedSnapshot("Films", 2, 0, 4), LATER);

    tell(SCAN_OR_REFRESH_FINISHED, new ScanOrRefreshFinishedSnapshot("Movies", 3, 1, 0), EARLIER);

    assertThat(rows.of(ava))
        .singleElement()
        .satisfies(
            updated -> {
              assertThat(updated.snapshot())
                  .isEqualTo(document(new ScanOrRefreshFinishedSnapshot("Films", 5, 1, 4)));
              assertThat(updated.latestEventAt()).isEqualTo(LATER);
            });
  }

  @Test
  @DisplayName("Should make one news for each recipient when a library kind is told")
  void shouldMakeOneNewsForEachRecipientWhenALibraryKindIsTold() {
    var snapshot = new ScanOrRefreshFinishedSnapshot("Movies", 3, 1, 0);

    publish(
        TellNotificationEvent.builder()
            .kind(SCAN_OR_REFRESH_FINISHED)
            .subject(movies)
            .recipients(Set.of(ava, ben))
            .snapshot(snapshot)
            .at(EARLIER)
            .build());

    assertThat(rows.about(movies)).extracting(Row::recipient).containsExactlyInAnyOrder(ava, ben);
    assertThat(rows.about(movies)).extracting(Row::snapshot).containsOnly(document(snapshot));
  }

  @Test
  @DisplayName("Should keep the two library kinds side by side when both are told about a library")
  void shouldKeepTheTwoLibraryKindsSideBySideWhenBothAreToldAboutALibrary() {
    tell(SCAN_OR_REFRESH_FINISHED, new ScanOrRefreshFinishedSnapshot("Movies", 3, 1, 0), EARLIER);

    tell(UNMATCHED_ITEMS, new UnmatchedItemsSnapshot("Movies", 14), EARLIER);

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
    tell(SCAN_OR_REFRESH_FINISHED, first, EARLIER);
    markRead(ava);
    var later = new ScanOrRefreshFinishedSnapshot("Movies", 2, 0, 4);

    tell(SCAN_OR_REFRESH_FINISHED, later, LATER);

    assertThat(rows.of(ava))
        .extracting(Row::snapshot, row -> row.readAt().isPresent())
        .containsExactlyInAnyOrder(tuple(document(first), true), tuple(document(later), false));
  }

  @Test
  @DisplayName(
      "Should replace the number of unmatched items when a later scan adds unmatched items")
  void shouldReplaceTheNumberWhenALaterScanAddsUnmatchedItems() {
    tell(UNMATCHED_ITEMS, new UnmatchedItemsSnapshot("Movies", 14), EARLIER);

    tell(UNMATCHED_ITEMS, new UnmatchedItemsSnapshot("Movies", 20), LATER);

    assertThat(rows.of(ava))
        .singleElement()
        .satisfies(
            updated -> {
              assertThat(updated.snapshot())
                  .isEqualTo(document(new UnmatchedItemsSnapshot("Movies", 20)));
              assertThat(updated.latestEventAt()).isEqualTo(LATER);
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
            .at(EARLIER)
            .build());
    tell(UNMATCHED_ITEMS, new UnmatchedItemsSnapshot("Movies", 14), EARLIER);

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

  @Test
  @DisplayName(
      "Should keep one unread Management removed, with the second removal's time and name, when"
          + " it is removed twice before it is read")
  void
      shouldKeepOneUnreadManagementRemovedWithTheSecondRemovalsTimeAndNameWhenItIsRemovedTwiceBeforeItIsRead() {
    var ollie = SubjectReference.profile(UUID.randomUUID());
    publish(managementRemoved(ollie, new ManagementRemovedSnapshot("Ollie"), EARLIER));
    var made = rows.of(ava).getFirst();

    publish(managementRemoved(ollie, new ManagementRemovedSnapshot("Ollie Brook"), LATER));

    assertThat(rows.of(ava))
        .singleElement()
        .satisfies(
            updated -> {
              assertThat(updated.id()).isEqualTo(made.id());
              assertThat(updated.snapshot())
                  .isEqualTo(document(new ManagementRemovedSnapshot("Ollie Brook")));
              assertThat(updated.latestEventAt()).isEqualTo(LATER);
              assertThat(updated.readAt()).isEmpty();
            });
  }

  @Test
  @DisplayName(
      "Should make a new Management removed when the Account read the first before the next"
          + " removal")
  void shouldMakeANewManagementRemovedWhenTheAccountReadTheFirstBeforeTheNextRemoval() {
    var ollie = SubjectReference.profile(UUID.randomUUID());
    var first = new ManagementRemovedSnapshot("Ollie");
    publish(managementRemoved(ollie, first, EARLIER));
    markRead(ava);
    var second = new ManagementRemovedSnapshot("Ollie Brook");

    publish(managementRemoved(ollie, second, LATER));

    assertThat(rows.of(ava))
        .extracting(Row::snapshot, row -> row.readAt().isPresent())
        .containsExactlyInAnyOrder(tuple(document(first), true), tuple(document(second), false));
  }

  @Test
  @DisplayName(
      "Should keep two invitation requests waiting for one Account until one is accepted and the"
          + " other withdrawn")
  void shouldKeepTwoInvitationRequestsWaitingForOneAccountUntilOneIsWithdrawn() {
    var toHome = SubjectReference.profileManagerInvitation(UUID.randomUUID());
    var toWork = SubjectReference.profileManagerInvitation(UUID.randomUUID());
    publish(invited(toHome).build());
    publish(invited(toWork).build());
    assertThat(rows.of(ava))
        .extracting(Row::subject, Row::resolution)
        .containsExactlyInAnyOrder(
            tuple(toHome, Optional.empty()), tuple(toWork, Optional.empty()));

    publish(
        resolution(toHome, RequestResolution.DECIDED)
            .answer(Optional.of(InvitationAnswer.ACCEPTED))
            .build());
    publish(resolution(toWork, RequestResolution.WITHDRAWN).build());

    assertThat(rows.of(ava))
        .extracting(Row::subject, Row::resolution)
        .containsExactlyInAnyOrder(
            tuple(toHome, Optional.of("DECIDED")), tuple(toWork, Optional.of("WITHDRAWN")));
    assertThat(rows.about(toHome))
        .extracting(Row::snapshot)
        .containsExactly(
            document(
                new InvitationReceivedSnapshot("Ollie", Optional.of(InvitationAnswer.ACCEPTED))));
  }

  @Test
  @DisplayName("Should make no request when an invitation is told to nobody")
  void shouldMakeNoRequestWhenAnInvitationIsToldToNobody() {
    var invitation = SubjectReference.profileManagerInvitation(UUID.randomUUID());

    publish(invited(invitation).recipients(Set.of()).build());

    assertThat(rows.about(invitation)).isEmpty();
  }

  /** Tells Ava that its management of the Profile was removed, as Profile management will. */
  private TellNotificationEvent managementRemoved(
      SubjectReference profile, ManagementRemovedSnapshot snapshot, Instant at) {
    return TellNotificationEvent.builder()
        .kind(SpecifiedOnlyNotificationKinds.MANAGEMENT_REMOVED)
        .subject(profile)
        .recipients(Set.of(ava))
        .snapshot(snapshot)
        .at(at)
        .build();
  }

  /** Asks Ava to accept an invitation to manage Ollie, which waits for a week. */
  private TellNotificationEvent.TellNotificationEventBuilder invited(SubjectReference invitation) {
    return TellNotificationEvent.builder()
        .kind(SpecifiedOnlyNotificationKinds.INVITATION_RECEIVED)
        .subject(invitation)
        .recipients(Set.of(ava))
        .snapshot(new InvitationReceivedSnapshot("Ollie", Optional.empty()))
        .at(EARLIER)
        .subjectExpiresAt(Optional.of(EARLIER.plus(Duration.ofDays(7))));
  }

  private static ResolveRequestsEvent.ResolveRequestsEventBuilder resolution(
      SubjectReference invitation, RequestResolution resolution) {
    return ResolveRequestsEvent.builder()
        .kind(SpecifiedOnlyNotificationKinds.INVITATION_RECEIVED)
        .subject(invitation)
        .resolution(resolution)
        .at(LATER);
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
