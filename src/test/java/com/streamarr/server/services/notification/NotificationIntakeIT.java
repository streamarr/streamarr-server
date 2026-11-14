package com.streamarr.server.services.notification;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static com.streamarr.server.support.StoredNotificationRows.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.notification.ChangedNotification;
import com.streamarr.server.domain.notification.DismissalSelector;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.RequestAnswer;
import com.streamarr.server.domain.notification.RequestResolution;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.services.events.notification.DismissNotificationsEvent;
import com.streamarr.server.services.events.notification.NotificationRecipientDeletedEvent;
import com.streamarr.server.services.events.notification.ProfileShareAnswer;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareEndedBy;
import com.streamarr.server.services.events.notification.ProfileShareEndedSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareOfferedSnapshot;
import com.streamarr.server.services.events.notification.ResolveRequestsEvent;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.notification.events.NotificationsChangedEvent;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.StoredNotificationRows.Row;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
  private static final Instant OFFER_EXPIRY = EVENT.plus(Duration.ofDays(7));

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
        .hasSize(2)
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
  @DisplayName("Should refuse a tell when it is published outside a transaction")
  void shouldRefuseATellWhenItIsPublishedOutsideATransaction() {
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

  @Test
  @DisplayName(
      "Should make one waiting request for each recipient with its subject's expiry when a request"
          + " is told")
  void shouldMakeOneWaitingRequestForEachRecipientWithItsSubjectsExpiryWhenARequestIsTold() {
    var snapshot = new ProfileShareOfferedSnapshot("Theo", Optional.empty());

    tell(offered(snapshot, share).recipients(Set.of(sam, kai)).build());

    assertThat(rows.about(share)).extracting(Row::recipient).containsExactlyInAnyOrder(sam, kai);
    assertThat(rows.about(share))
        .hasSize(2)
        .allSatisfy(
            row -> {
              assertThat(row.kind()).isEqualTo("PROFILE_SHARE_OFFERED");
              assertThat(row.form()).isEqualTo("REQUEST");
              assertThat(row.snapshot()).isEqualTo(document(snapshot));
              assertThat(row.latestEventAt()).isEqualTo(EVENT);
              assertThat(row.expiresAt()).contains(OFFER_EXPIRY);
              assertThat(row.resolution()).isEmpty();
              assertThat(row.resolvedAt()).isEmpty();
              assertThat(row.readAt()).isEmpty();
            });
    assertThat(announced().getFirst().notifications())
        .containsExactlyInAnyOrderElementsOf(changesOf(rows.about(share)));
  }

  @Test
  @DisplayName("Should make no second request when the same request is told again")
  void shouldMakeNoSecondRequestWhenTheSameRequestIsToldAgain() {
    var snapshot = new ProfileShareOfferedSnapshot("Theo", Optional.empty());
    tell(offered(snapshot, share).build());

    tell(
        offered(new ProfileShareOfferedSnapshot("Theo Brook", Optional.empty()), share)
            .at(EVENT.plusSeconds(60))
            .build());

    assertThat(onlyRowOf(sam))
        .satisfies(
            row -> {
              assertThat(row.snapshot()).isEqualTo(document(snapshot));
              assertThat(row.latestEventAt()).isEqualTo(EVENT);
            });
    assertThat(announced()).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should make a request for each subject when a newer proposal is told beside an older one")
  void shouldMakeARequestForEachSubjectWhenANewerProposalIsToldBesideAnOlderOne() {
    var newer = SubjectReference.profileShare(UUID.randomUUID());
    tell(offered(new ProfileShareOfferedSnapshot("Theo", Optional.empty()), share).build());

    tell(
        offered(new ProfileShareOfferedSnapshot("Theo", Optional.empty()), newer)
            .at(EVENT.plusSeconds(60))
            .build());

    assertThat(rows.of(sam)).extracting(Row::subject).containsExactly(share, newer);
  }

  @Test
  @DisplayName(
      "Should resolve every waiting request about the subject as decided with the answer when it"
          + " is decided")
  void shouldResolveEveryWaitingRequestAboutTheSubjectAsDecidedWithTheAnswerWhenItIsDecided() {
    tell(offeredTheo().recipients(Set.of(sam, kai)).build());

    publish(decided(EVENT.plusSeconds(60)).build());

    assertThat(rows.about(share))
        .hasSize(2)
        .allSatisfy(
            row -> {
              assertThat(row.resolution()).contains("DECIDED");
              assertThat(row.resolvedAt()).contains(EVENT.plusSeconds(60));
              assertThat(row.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileShareOfferedSnapshot(
                              "Theo", Optional.of(ProfileShareAnswer.ACCEPTED))));
            });
  }

  @Test
  @DisplayName("Should resolve as withdrawn without an answer when the subject is withdrawn")
  void shouldResolveAsWithdrawnWithoutAnAnswerWhenTheSubjectIsWithdrawn() {
    tell(offeredTheo().build());

    publish(withdrawn(EVENT.plusSeconds(60)).build());

    assertThat(onlyRowOf(sam))
        .satisfies(
            row -> {
              assertThat(row.resolution()).contains("WITHDRAWN");
              assertThat(row.resolvedAt()).contains(EVENT.plusSeconds(60));
              assertThat(row.snapshot())
                  .isEqualTo(document(new ProfileShareOfferedSnapshot("Theo", Optional.empty())));
            });
  }

  @Test
  @DisplayName(
      "Should write the decision and its instant when its instant is before the expiry but it"
          + " arrives after it")
  void shouldWriteTheDecisionAndItsInstantWhenItsInstantIsBeforeTheExpiryButItArrivesAfterIt() {
    var expiry = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
    tell(
        offeredTheo()
            .at(expiry.minus(Duration.ofDays(7)))
            .subjectExpiresAt(Optional.of(expiry))
            .build());

    publish(decided(expiry.minusSeconds(1)).build());

    assertThat(onlyRowOf(sam))
        .satisfies(
            row -> {
              assertThat(row.resolution()).contains("DECIDED");
              assertThat(row.resolvedAt()).contains(expiry.minusSeconds(1));
              assertThat(row.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileShareOfferedSnapshot(
                              "Theo", Optional.of(ProfileShareAnswer.ACCEPTED))));
            });
  }

  static Stream<Arguments> resolutionsAtOrAfterTheExpiry() {
    return Stream.of(
        Arguments.of(
            RequestResolution.DECIDED, Optional.of(ProfileShareAnswer.ACCEPTED), Duration.ZERO),
        Arguments.of(
            RequestResolution.DECIDED,
            Optional.of(ProfileShareAnswer.ACCEPTED),
            Duration.ofSeconds(1)),
        Arguments.of(RequestResolution.WITHDRAWN, Optional.empty(), Duration.ZERO),
        Arguments.of(RequestResolution.WITHDRAWN, Optional.empty(), Duration.ofSeconds(1)));
  }

  @ParameterizedTest(name = "{0} {2} after the expiry")
  @MethodSource("resolutionsAtOrAfterTheExpiry")
  @DisplayName(
      "Should write expired at the expiry when a resolution's instant is at or after the expiry")
  void shouldWriteExpiredAtTheExpiryWhenAResolutionsInstantIsAtOrAfterTheExpiry(
      RequestResolution resolution, Optional<ProfileShareAnswer> answer, Duration afterTheExpiry) {
    tell(offeredTheo().build());

    publish(
        ResolveRequestsEvent.builder()
            .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
            .subject(share)
            .resolution(resolution)
            .answer(answer.map(RequestAnswer.class::cast))
            .at(OFFER_EXPIRY.plus(afterTheExpiry))
            .build());

    assertThat(onlyRowOf(sam))
        .satisfies(
            row -> {
              assertThat(row.resolution()).contains("EXPIRED");
              assertThat(row.resolvedAt()).contains(OFFER_EXPIRY);
              assertThat(row.snapshot())
                  .isEqualTo(document(new ProfileShareOfferedSnapshot("Theo", Optional.empty())));
            });
  }

  @Test
  @DisplayName("Should leave a request as it resolved when a later resolution arrives")
  void shouldLeaveARequestAsItResolvedWhenALaterResolutionArrives() {
    tell(offeredTheo().build());
    publish(withdrawn(EVENT.plusSeconds(60)).build());

    publish(decided(EVENT.plusSeconds(120)).build());

    assertThat(onlyRowOf(sam))
        .satisfies(
            row -> {
              assertThat(row.resolution()).contains("WITHDRAWN");
              assertThat(row.resolvedAt()).contains(EVENT.plusSeconds(60));
              assertThat(row.snapshot())
                  .isEqualTo(document(new ProfileShareOfferedSnapshot("Theo", Optional.empty())));
            });
    assertThat(announced()).hasSize(2);
  }

  @Test
  @DisplayName("Should keep the request's time and read state when it resolves")
  void shouldKeepTheRequestsTimeAndReadStateWhenItResolves() {
    tell(offeredTheo().recipients(Set.of(sam, kai)).build());
    markRead(onlyRowOf(sam).id());

    publish(decided(EVENT.plusSeconds(60)).build());

    assertThat(onlyRowOf(sam))
        .satisfies(
            row -> {
              assertThat(row.latestEventAt()).isEqualTo(EVENT);
              assertThat(row.readAt()).contains(EVENT);
            });
    assertThat(onlyRowOf(kai))
        .satisfies(
            row -> {
              assertThat(row.latestEventAt()).isEqualTo(EVENT);
              assertThat(row.readAt()).isEmpty();
            });
  }

  @Test
  @DisplayName("Should leave news and requests about other subjects alone when a request resolves")
  void shouldLeaveNewsAndRequestsAboutOtherSubjectsAloneWhenARequestResolves() {
    var other = SubjectReference.profileShare(UUID.randomUUID());
    tell(offeredTheo().build());
    tell(
        offered(new ProfileShareOfferedSnapshot("Theo", Optional.empty()), other)
            .at(EVENT.plusSeconds(1))
            .build());
    tell(
        answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED))
            .at(EVENT.plusSeconds(2))
            .build());

    publish(decided(EVENT.plusSeconds(60)).build());

    assertThat(rows.of(sam))
        .satisfiesExactly(
            resolved -> assertThat(resolved.resolution()).contains("DECIDED"),
            otherRequest -> assertThat(otherRequest.resolution()).isEmpty(),
            news ->
                assertThat(news.snapshot())
                    .isEqualTo(
                        document(
                            new ProfileShareAnsweredSnapshot(
                                "Theo", ProfileShareAnswer.ACCEPTED))));
  }

  @Test
  @DisplayName("Should announce the resolved requests to each recipient when requests resolve")
  void shouldAnnounceTheResolvedRequestsToEachRecipientWhenRequestsResolve() {
    tell(offeredTheo().recipients(Set.of(sam, kai)).build());

    publish(withdrawn(EVENT.plusSeconds(60)).build());

    assertThat(announced()).hasSize(2);
    assertThat(announced().getLast().notifications())
        .containsExactlyInAnyOrderElementsOf(changesOf(rows.about(share)));
  }

  @Test
  @DisplayName("Should delete exactly the Notifications named when they are dismissed by id")
  void shouldDeleteExactlyTheNotificationsNamedWhenTheyAreDismissedById() {
    var other = SubjectReference.profileShare(UUID.randomUUID());
    tell(
        answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED))
            .recipients(Set.of(sam, kai))
            .build());
    tell(offered(new ProfileShareOfferedSnapshot("Theo", Optional.empty()), other).build());
    var samsNews = rows.about(share).stream().filter(row -> row.recipient().equals(sam)).toList();
    var named = changesOf(samsNews).stream().map(ChangedNotification::notificationId).toList();

    dismiss(DismissalSelector.byId(Set.copyOf(named)).build());

    assertThat(rows.of(sam)).extracting(Row::subject).containsExactly(other);
    assertThat(rows.of(kai)).extracting(Row::subject).containsExactly(share);
  }

  @Test
  @DisplayName(
      "Should delete every Notification about the subjects for every recipient when they are"
          + " dismissed by subject")
  void
      shouldDeleteEveryNotificationAboutTheSubjectsForEveryRecipientWhenTheyAreDismissedBySubject() {
    var endedShare = SubjectReference.profileShare(UUID.randomUUID());
    var kept = SubjectReference.profileShare(UUID.randomUUID());
    tell(offeredTheo().recipients(Set.of(sam, kai)).build());
    tell(
        answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED))
            .recipients(Set.of(sam, kai))
            .build());
    tell(
        ended(new ProfileShareEndedSnapshot("Theo", ProfileShareEndedBy.SERVER))
            .subject(endedShare)
            .recipients(Set.of(sam, kai))
            .build());
    tell(
        offered(new ProfileShareOfferedSnapshot("Theo", Optional.empty()), kept)
            .recipients(Set.of(sam, kai))
            .build());

    dismiss(DismissalSelector.bySubject(Set.of(share, endedShare)).build());

    assertThat(rows.of(sam)).extracting(Row::subject).containsExactly(kept);
    assertThat(rows.of(kai)).extracting(Row::subject).containsExactly(kept);
  }

  @Test
  @DisplayName(
      "Should delete only the recipient's Notifications when the dismissal is narrowed to a"
          + " recipient")
  void shouldDeleteOnlyTheRecipientsNotificationsWhenTheDismissalIsNarrowedToARecipient() {
    tell(offeredTheo().recipients(Set.of(sam, kai)).build());

    dismiss(DismissalSelector.bySubject(Set.of(share)).recipient(Optional.of(sam)).build());

    assertThat(rows.of(sam)).isEmpty();
    assertThat(rows.of(kai)).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should keep another recipient's Notification when a dismissal by id is narrowed to a"
          + " recipient")
  void shouldKeepAnotherRecipientsNotificationWhenADismissalByIdIsNarrowedToARecipient() {
    tell(offeredTheo().recipients(Set.of(sam, kai)).build());
    var kaisRequest = onlyRowOf(kai).id();

    dismiss(DismissalSelector.byId(Set.of(kaisRequest)).recipient(Optional.of(sam)).build());

    assertThat(rows.of(kai)).extracting(Row::id).containsExactly(kaisRequest);
    assertThat(rows.of(sam)).hasSize(1);
  }

  @Test
  @DisplayName("Should delete only the kinds named when the dismissal is narrowed to kinds")
  void shouldDeleteOnlyTheKindsNamedWhenTheDismissalIsNarrowedToKinds() {
    tell(answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED)).build());
    tell(ended(new ProfileShareEndedSnapshot("Theo", ProfileShareEndedBy.SERVER)).build());

    dismiss(
        DismissalSelector.bySubject(Set.of(share))
            .kinds(Set.of(SharingNotificationKinds.PROFILE_SHARE_ENDED))
            .build());

    assertThat(rows.of(sam)).extracting(Row::kind).containsExactly("PROFILE_SHARE_ANSWERED");
  }

  @Test
  @DisplayName("Should delete only news when the dismissal is narrowed to news")
  void shouldDeleteOnlyNewsWhenTheDismissalIsNarrowedToNews() {
    tell(offeredTheo().build());
    tell(answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED)).build());

    dismiss(
        DismissalSelector.bySubject(Set.of(share))
            .form(Optional.of(DismissalSelector.DismissedForm.NEWS))
            .build());

    assertThat(rows.of(sam)).extracting(Row::form).containsExactly("REQUEST");
  }

  @Test
  @DisplayName(
      "Should delete only requests no event resolved when the dismissal is narrowed to waiting"
          + " requests")
  void shouldDeleteOnlyRequestsNoEventResolvedWhenTheDismissalIsNarrowedToWaitingRequests() {
    var resolved = SubjectReference.profileShare(UUID.randomUUID());
    var expired = SubjectReference.profileShare(UUID.randomUUID());
    tell(offeredTheo().build());
    tell(answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED)).build());
    tell(offered(new ProfileShareOfferedSnapshot("Theo", Optional.empty()), resolved).build());
    publish(withdrawn(EVENT.plusSeconds(60)).subject(resolved).build());
    tell(
        offered(new ProfileShareOfferedSnapshot("Theo", Optional.empty()), expired)
            .at(EVENT.minus(Duration.ofDays(8)))
            .subjectExpiresAt(Optional.of(EVENT.minus(Duration.ofDays(1))))
            .build());

    dismiss(
        DismissalSelector.bySubject(Set.of(share, resolved, expired))
            .form(Optional.of(DismissalSelector.DismissedForm.WAITING_REQUESTS))
            .build());

    assertThat(rows.of(sam))
        .extracting(Row::subject, Row::form)
        .containsExactlyInAnyOrder(tuple(share, "NEWS"), tuple(resolved, "REQUEST"));
  }

  @Test
  @DisplayName("Should delete nothing when the dismissal's transaction rolls back")
  void shouldDeleteNothingWhenTheDismissalsTransactionRollsBack() {
    tell(offeredTheo().build());

    transactionTemplate.executeWithoutResult(
        status -> {
          eventPublisher.publishEvent(
              new DismissNotificationsEvent(DismissalSelector.bySubject(Set.of(share)).build()));
          status.setRollbackOnly();
        });

    assertThat(rows.of(sam)).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should announce each dismissed Notification to the recipient that lost it when Notifications"
          + " are dismissed")
  void shouldAnnounceEachDismissedNotificationToItsRecipientWhenNotificationsAreDismissed() {
    tell(offeredTheo().recipients(Set.of(sam, kai)).build());
    tell(answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED)).build());
    var lost = changesOf(rows.about(share));

    dismiss(DismissalSelector.bySubject(Set.of(share)).build());

    assertThat(announced()).hasSize(3);
    assertThat(announced().getLast().notifications()).containsExactlyInAnyOrderElementsOf(lost);
  }

  @Test
  @DisplayName(
      "Should delete every Notification of the recipient and no other when its recipient is"
          + " deleted")
  void shouldDeleteEveryNotificationOfTheRecipientAndNoOtherWhenItsRecipientIsDeleted() {
    tell(offeredTheo().recipients(Set.of(sam, kai)).build());
    tell(
        answered(new ProfileShareAnsweredSnapshot("Theo", ProfileShareAnswer.ACCEPTED))
            .recipients(Set.of(sam, kai))
            .build());

    publish(new NotificationRecipientDeletedEvent(sam));

    assertThat(rows.of(sam)).isEmpty();
    assertThat(rows.of(kai)).hasSize(2);
  }

  @Test
  @DisplayName("Should announce no change when a recipient is deleted")
  void shouldAnnounceNoChangeWhenARecipientIsDeleted() {
    tell(offeredTheo().build());

    publish(new NotificationRecipientDeletedEvent(sam));

    assertThat(announced()).hasSize(1);
  }

  private void dismiss(DismissalSelector selector) {
    publish(new DismissNotificationsEvent(selector));
  }

  private void tell(TellNotificationEvent tell) {
    publish(tell);
  }

  private void publish(Object event) {
    transactionTemplate.executeWithoutResult(_ -> eventPublisher.publishEvent(event));
  }

  private TellNotificationEvent.TellNotificationEventBuilder offeredTheo() {
    return offered(new ProfileShareOfferedSnapshot("Theo", Optional.empty()), share);
  }

  private ResolveRequestsEvent.ResolveRequestsEventBuilder decided(Instant at) {
    return ResolveRequestsEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
        .subject(share)
        .resolution(RequestResolution.DECIDED)
        .answer(Optional.of(ProfileShareAnswer.ACCEPTED))
        .at(at);
  }

  private ResolveRequestsEvent.ResolveRequestsEventBuilder withdrawn(Instant at) {
    return ResolveRequestsEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
        .subject(share)
        .resolution(RequestResolution.WITHDRAWN)
        .at(at);
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

  private TellNotificationEvent.TellNotificationEventBuilder offered(
      ProfileShareOfferedSnapshot snapshot, SubjectReference offer) {
    return TellNotificationEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_OFFERED)
        .subject(offer)
        .recipients(Set.of(sam))
        .snapshot(snapshot)
        .at(EVENT)
        .subjectExpiresAt(Optional.of(OFFER_EXPIRY));
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
