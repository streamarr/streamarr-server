package com.streamarr.server.services.notification;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static com.streamarr.server.support.StoredNotificationRows.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.notification.ProfileShareAnswer;
import com.streamarr.server.domain.notification.ProfileShareEndedBy;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareEndedSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.notification.events.ChangedNotification;
import com.streamarr.server.services.notification.events.NotificationsChangedEvent;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.StoredNotificationRows.Row;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the module keeps when a domain tells it, resolves or dismisses (ADR 0063 §How the server
 * builds it). The recipients and subjects are references the module never looks up, so the tests
 * name made-up ids.
 */
@Tag("IntegrationTest")
@DisplayName("Notification Intake Integration Tests")
@RecordApplicationEvents
class NotificationIntakeIT extends AbstractIntegrationTest {

  private static final Instant EVENT = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private ApplicationEvents applicationEvents;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;

  private final RecipientReference sam = RecipientReference.account(UUID.randomUUID());
  private final RecipientReference kai = RecipientReference.account(UUID.randomUUID());
  private final SubjectReference share = SubjectReference.profileShare(UUID.randomUUID());

  private StoredNotificationRows rows;

  @BeforeEach
  void setUp() {
    rows = new StoredNotificationRows(dsl);
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(NOTIFICATION).where(NOTIFICATION.RECIPIENT_ID.in(sam.id(), kai.id())).execute();
  }

  @Test
  @DisplayName("Should make one unread news for each recipient when news is told")
  void shouldMakeOneUnreadNewsForEachRecipientWhenNewsIsTold() {
    var snapshot = new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED);

    tell(answered(snapshot).recipients(Set.of(sam, kai)).build());

    assertThat(rows.about(share)).extracting(Row::recipient).containsExactlyInAnyOrder(sam, kai);
    assertThat(rows.about(share))
        .allSatisfy(
            row -> {
              assertThat(row.kind()).isEqualTo("PROFILE_SHARE_ANSWERED");
              assertThat(row.form()).isEqualTo("NEWS");
              assertThat(row.subject()).isEqualTo(share);
              assertThat(row.snapshot()).isEqualTo(document(snapshot));
              assertThat(row.latestEventAt()).isEqualTo(EVENT);
              assertThat(row.readAt()).isEmpty();
              assertThat(row.resolution()).isEmpty();
              assertThat(row.expiresAt()).isEmpty();
            });
  }

  @Test
  @DisplayName(
      "Should replace the snapshot and move the time of the unread news when its kind is told again"
          + " about its subject")
  void
      shouldReplaceTheSnapshotAndMoveTheTimeOfTheUnreadNewsWhenItsKindIsToldAgainAboutItsSubject() {
    tell(ended(new ProfileShareEndedSnapshot("Theo", ProfileShareEndedBy.HOUSEHOLD_SIDE)).build());
    var made = onlyRowOf(sam);
    var later = new ProfileShareEndedSnapshot("Theo Brook", ProfileShareEndedBy.SERVER);

    tell(ended(later).at(EVENT.plusSeconds(60)).build());

    assertThat(onlyRowOf(sam))
        .satisfies(
            row -> {
              assertThat(row.id()).isEqualTo(made.id());
              assertThat(row.snapshot()).isEqualTo(document(later));
              assertThat(row.latestEventAt()).isEqualTo(EVENT.plusSeconds(60));
              assertThat(row.readAt()).isEmpty();
            });
  }

  @Test
  @DisplayName(
      "Should keep the later snapshot and time when an earlier event of the kind arrives late")
  void shouldKeepTheLaterSnapshotAndTimeWhenAnEarlierEventOfTheKindArrivesLate() {
    var later = new ProfileShareEndedSnapshot("Theo Brook", ProfileShareEndedBy.SERVER);
    tell(ended(later).at(EVENT.plusSeconds(60)).build());

    tell(ended(new ProfileShareEndedSnapshot("Theo", ProfileShareEndedBy.HOUSEHOLD_SIDE)).build());

    assertThat(onlyRowOf(sam))
        .satisfies(
            row -> {
              assertThat(row.snapshot()).isEqualTo(document(later));
              assertThat(row.latestEventAt()).isEqualTo(EVENT.plusSeconds(60));
            });
  }

  @Test
  @DisplayName("Should make new news when the recipient read the earlier news of the kind")
  void shouldMakeNewNewsWhenTheRecipientReadTheEarlierNewsOfTheKind() {
    var earlier = new ProfileShareEndedSnapshot("Theo", ProfileShareEndedBy.HOUSEHOLD_SIDE);
    tell(ended(earlier).build());
    var read = onlyRowOf(sam);
    markRead(read.id());
    var later = new ProfileShareEndedSnapshot("Theo Brook", ProfileShareEndedBy.SERVER);

    tell(ended(later).at(EVENT.plusSeconds(60)).build());

    assertThat(rows.of(sam))
        .satisfiesExactly(
            first -> {
              assertThat(first.id()).isEqualTo(read.id());
              assertThat(first.snapshot()).isEqualTo(document(earlier));
              assertThat(first.latestEventAt()).isEqualTo(EVENT);
              assertThat(first.readAt()).isPresent();
            },
            second -> {
              assertThat(second.snapshot()).isEqualTo(document(later));
              assertThat(second.latestEventAt()).isEqualTo(EVENT.plusSeconds(60));
              assertThat(second.readAt()).isEmpty();
            });
  }

  @Test
  @DisplayName("Should keep news of another kind apart when it is told about the same subject")
  void shouldKeepNewsOfAnotherKindApartWhenItIsToldAboutTheSameSubject() {
    tell(answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED)).build());

    tell(
        ended(new ProfileShareEndedSnapshot("Theo", ProfileShareEndedBy.HOUSEHOLD_SIDE))
            .at(EVENT.plusSeconds(60))
            .build());

    assertThat(rows.of(sam))
        .extracting(Row::kind)
        .containsExactly("PROFILE_SHARE_ANSWERED", "PROFILE_SHARE_ENDED");
  }

  @Test
  @DisplayName(
      "Should keep one unread news when two events of the kind about one subject arrive at once")
  void shouldKeepOneUnreadNewsWhenTwoEventsOfTheKindAboutOneSubjectArriveAtOnce() throws Exception {
    var firstHolds = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var firstPid = new AtomicInteger();
    var later = new ProfileShareEndedSnapshot("Theo Brook", ProfileShareEndedBy.SERVER);

    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var first =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        eventPublisher.publishEvent(
                            ended(
                                    new ProfileShareEndedSnapshot(
                                        "Theo", ProfileShareEndedBy.HOUSEHOLD_SIDE))
                                .build());
                        firstPid.set(backendPid());
                        firstHolds.countDown();
                        awaitLatch(releaseFirst);
                      }));
      awaitLatch(firstHolds);
      var second = executor.submit(() -> tell(ended(later).at(EVENT.plusSeconds(1)).build()));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitersBehind(jdbcTemplate, firstPid.get(), "insert%notification%") == 1);

      releaseFirst.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }

    assertThat(onlyRowOf(sam))
        .satisfies(
            row -> {
              assertThat(row.snapshot()).isEqualTo(document(later));
              assertThat(row.latestEventAt()).isEqualTo(EVENT.plusSeconds(1));
            });
  }

  @Test
  @DisplayName("Should store nothing when the tell's transaction rolls back")
  void shouldStoreNothingWhenTheTellsTransactionRollsBack() {
    transactionTemplate.executeWithoutResult(
        status -> {
          eventPublisher.publishEvent(
              answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED))
                  .build());
          status.setRollbackOnly();
        });

    assertThat(rows.about(share)).isEmpty();
  }

  @Test
  @DisplayName("Should refuse a tell published outside a transaction")
  void shouldRefuseATellPublishedOutsideATransaction() {
    var tell =
        answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED)).build();

    assertThatThrownBy(() -> eventPublisher.publishEvent(tell))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(rows.about(share)).isEmpty();
  }

  @Test
  @DisplayName("Should announce one change for each recipient when news is told")
  void shouldAnnounceOneChangeForEachRecipientWhenNewsIsTold() {
    tell(
        answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED))
            .recipients(Set.of(sam, kai))
            .build());

    assertThat(announced()).hasSize(1);
    assertThat(announced().getFirst().notifications())
        .containsExactlyInAnyOrderElementsOf(changesOf(rows.about(share)));
  }

  @Test
  @DisplayName("Should announce the unread news it updated in place when its kind is told again")
  void shouldAnnounceTheUnreadNewsItUpdatedInPlaceWhenItsKindIsToldAgain() {
    tell(ended(new ProfileShareEndedSnapshot("Theo", ProfileShareEndedBy.HOUSEHOLD_SIDE)).build());

    tell(
        ended(new ProfileShareEndedSnapshot("Theo Brook", ProfileShareEndedBy.SERVER))
            .at(EVENT.plusSeconds(60))
            .build());

    assertThat(announced())
        .extracting(NotificationsChangedEvent::notifications)
        .hasSize(2)
        .allSatisfy(changes -> assertThat(changes).isEqualTo(changesOf(rows.of(sam))));
  }

  @Test
  @DisplayName("Should store and announce nothing when news is told to nobody")
  void shouldStoreAndAnnounceNothingWhenNewsIsToldToNobody() {
    tell(
        answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED))
            .recipients(Set.of())
            .build());

    assertThat(rows.about(share)).isEmpty();
    assertThat(announced()).isEmpty();
  }

  private void tell(TellNotificationEvent tell) {
    transactionTemplate.executeWithoutResult(_ -> eventPublisher.publishEvent(tell));
  }

  private TellNotificationEvent.TellNotificationEventBuilder answered(
      ProfileShareAnsweredSnapshot snapshot) {
    return TellNotificationEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_ANSWERED)
        .subject(share)
        .recipients(Set.of(sam))
        .snapshot(snapshot)
        .at(EVENT);
  }

  private TellNotificationEvent.TellNotificationEventBuilder ended(
      ProfileShareEndedSnapshot snapshot) {
    return TellNotificationEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_ENDED)
        .subject(share)
        .recipients(Set.of(sam))
        .snapshot(snapshot)
        .at(EVENT);
  }

  private Row onlyRowOf(RecipientReference recipient) {
    var stored = rows.of(recipient);
    assertThat(stored).hasSize(1);
    return stored.getFirst();
  }

  private void markRead(UUID notificationId) {
    transactionTemplate.executeWithoutResult(
        _ ->
            dsl.update(NOTIFICATION)
                .set(NOTIFICATION.READ_AT, EVENT.atOffset(ZoneOffset.UTC))
                .where(NOTIFICATION.ID.eq(notificationId))
                .execute());
  }

  private List<NotificationsChangedEvent> announced() {
    return applicationEvents.stream(NotificationsChangedEvent.class).toList();
  }

  private static List<ChangedNotification> changesOf(List<Row> stored) {
    return stored.stream().map(row -> new ChangedNotification(row.id(), row.recipient())).toList();
  }

  private int backendPid() {
    return dsl.select(DSL.field("pg_backend_pid()", SQLDataType.INTEGER)).fetchSingle().value1();
  }
}
