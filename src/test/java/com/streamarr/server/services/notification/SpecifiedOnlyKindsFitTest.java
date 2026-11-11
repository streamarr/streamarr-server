package com.streamarr.server.services.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.InMemoryNotificationStore;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR 0063 specifies kinds that no build makes yet. The two library kinds, "Scan or refresh
 * finished" and "Unmatched items", are news about one library for every ServerAdmin, and a later
 * event of each adds its figures to the unread one. The two ProfileManager invitation kinds are a
 * request for the invitation's named Account, "Invitation received", and news for the Account that
 * issued it, "Invitation answered". They are defined here, in test code alone, to show that the
 * writer, its noise rule and its recipients rule take them without a change: their recipients rule,
 * their subject, their form and their one unread key fit the shape the sharing kinds use.
 */
@Tag("UnitTest")
@DisplayName("Specified-Only Kinds Fit Tests")
class SpecifiedOnlyKindsFitTest {

  private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
  private static final UUID MOVIES = UUID.randomUUID();
  private static final UUID SAM = UUID.randomUUID();
  private static final UUID KAI = UUID.randomUUID();
  private static final Set<UUID> SERVER_ADMINS = Set.of(SAM, KAI);
  private static final UUID OLDER_INVITATION = UUID.randomUUID();
  private static final UUID NEWER_INVITATION = UUID.randomUUID();

  private final InMemoryNotificationStore<LibraryKind, LibraryFacts> store =
      new InMemoryNotificationStore<>(LibraryKind::getForm);
  private final NotificationWriter<LibraryKind, LibraryFacts> writer =
      new NotificationWriter<>(new LibraryKinds(), store, new CapturingEventPublisher());
  private final InMemoryNotificationStore<InvitationKind, InvitationFacts> invitationStore =
      new InMemoryNotificationStore<>(InvitationKind::getForm);
  private final NotificationWriter<InvitationKind, InvitationFacts> invitations =
      new NotificationWriter<>(
          new InvitationKinds(), invitationStore, new CapturingEventPublisher());

  @Test
  @DisplayName(
      "Should tell every ServerAdmin, the one that started it included, when a scan finishes")
  void shouldTellEveryServerAdminTheOneThatStartedItIncludedWhenAScanFinishes() {
    // Sam started the scan, but its end is nobody's action, so the event names no acting Account.
    writer.tell(
        NotificationEvent.<LibraryKind, LibraryFacts>builder()
            .kind(LibraryKind.SCAN_OR_REFRESH_FINISHED)
            .subjectId(MOVIES)
            .facts(new ScanFigures(14, 0, 2, NOW))
            .recipients(SERVER_ADMINS)
            .actingAccountId(Optional.empty())
            .at(NOW)
            .build());

    assertThat(store.rows())
        .extracting(InMemoryNotificationStore.Stored::recipientAccountId)
        .containsExactlyInAnyOrder(SAM, KAI);
    assertThat(store.rows())
        .allSatisfy(
            stored -> {
              assertThat(stored.subjectId()).isEqualTo(MOVIES);
              assertThat(stored.kind()).isEqualTo(LibraryKind.SCAN_OR_REFRESH_FINISHED);
            });
  }

  @Test
  @DisplayName(
      "Should add the figures to the unread news when a later scan of the library finishes")
  void shouldAddTheFiguresToTheUnreadNewsWhenALaterScanOfTheLibraryFinishes() {
    writer.tell(scanFinished(new ScanFigures(14, 0, 2, NOW), NOW));

    writer.tell(
        scanFinished(new ScanFigures(6, 1, 0, NOW.plusSeconds(86_400)), NOW.plusSeconds(86_400)));

    assertThat(store.rowsOf(SAM))
        .singleElement()
        .satisfies(
            stored -> {
              assertThat(stored.facts())
                  .isEqualTo(new ScanFigures(20, 1, 2, NOW.plusSeconds(86_400)));
              assertThat(stored.latestEventAt()).isEqualTo(NOW.plusSeconds(86_400));
            });
  }

  @Test
  @DisplayName("Should keep the two library kinds side by side when both are told about a library")
  void shouldKeepTheTwoLibraryKindsSideBySideWhenBothAreToldAboutALibrary() {
    writer.tell(scanFinished(new ScanFigures(14, 0, 0, NOW), NOW));
    writer.tell(unmatched(14, NOW));

    writer.tell(unmatched(6, NOW.plusSeconds(86_400)));

    assertThat(store.rowsOf(SAM))
        .extracting(InMemoryNotificationStore.Stored::facts)
        .containsExactlyInAnyOrder(new ScanFigures(14, 0, 0, NOW), new UnmatchedItems(20));
  }

  @Test
  @DisplayName("Should make new news when the ServerAdmin read the earlier news about the library")
  void shouldMakeNewNewsWhenTheServerAdminReadTheEarlierNewsAboutTheLibrary() {
    var read =
        writer.tell(unmatched(14, NOW)).stream()
            .filter(written -> written.recipientAccountId().equals(SAM))
            .findFirst()
            .orElseThrow();
    store.markRead(read.notificationId(), NOW.plusSeconds(60));

    writer.tell(unmatched(6, NOW.plusSeconds(86_400)));

    assertThat(store.rowsOf(SAM))
        .extracting(InMemoryNotificationStore.Stored::facts)
        .containsExactly(new UnmatchedItems(14), new UnmatchedItems(6));
    assertThat(store.rowsOf(KAI))
        .extracting(InMemoryNotificationStore.Stored::facts)
        .containsExactly(new UnmatchedItems(20));
  }

  @Test
  @DisplayName(
      "Should give the named Account a new request when a newer invitation replaces an older one")
  void shouldGiveTheNamedAccountANewRequestWhenANewerInvitationReplacesAnOlderOne() {
    invitations.tell(received(OLDER_INVITATION, NOW));

    invitations.tell(received(NEWER_INVITATION, NOW.plusSeconds(60)));

    assertThat(invitationStore.rowsOf(KAI))
        .extracting(InMemoryNotificationStore.Stored::subjectId)
        .containsExactly(OLDER_INVITATION, NEWER_INVITATION);
    assertThat(invitationStore.rowsOf(SAM)).isEmpty();
  }

  @Test
  @DisplayName("Should tell the Account that issued the invitation when the named Account answers")
  void shouldTellTheAccountThatIssuedTheInvitationWhenTheNamedAccountAnswers() {
    invitations.tell(received(NEWER_INVITATION, NOW));

    invitations.tell(
        NotificationEvent.<InvitationKind, InvitationFacts>builder()
            .kind(InvitationKind.ANSWERED)
            .subjectId(NEWER_INVITATION)
            .facts(new InvitationAnswer(true))
            .recipients(Set.of(SAM))
            .actingAccountId(Optional.of(KAI))
            .at(NOW.plusSeconds(60))
            .build());

    assertThat(invitationStore.rowsOf(SAM))
        .singleElement()
        .satisfies(
            stored -> {
              assertThat(stored.kind()).isEqualTo(InvitationKind.ANSWERED);
              assertThat(stored.facts()).isEqualTo(new InvitationAnswer(true));
            });
    assertThat(invitationStore.rowsOf(KAI))
        .extracting(InMemoryNotificationStore.Stored::kind)
        .containsExactly(InvitationKind.RECEIVED);
  }

  // Issuing an invitation makes a request for its named Account alone; the request carries
  // nothing beyond the invitation, and never its code.
  private static NotificationEvent<InvitationKind, InvitationFacts> received(
      UUID invitationId, Instant at) {
    return NotificationEvent.<InvitationKind, InvitationFacts>builder()
        .kind(InvitationKind.RECEIVED)
        .subjectId(invitationId)
        .facts(new InvitationWaiting())
        .recipients(Set.of(KAI))
        .actingAccountId(Optional.of(SAM))
        .at(at)
        .build();
  }

  // The end of a scan is nobody's action, so no Account is left out.
  private static NotificationEvent<LibraryKind, LibraryFacts> scanFinished(
      ScanFigures figures, Instant at) {
    return toEveryServerAdmin(LibraryKind.SCAN_OR_REFRESH_FINISHED, figures, at);
  }

  private static NotificationEvent<LibraryKind, LibraryFacts> unmatched(int added, Instant at) {
    return toEveryServerAdmin(LibraryKind.UNMATCHED_ITEMS, new UnmatchedItems(added), at);
  }

  private static NotificationEvent<LibraryKind, LibraryFacts> toEveryServerAdmin(
      LibraryKind kind, LibraryFacts facts, Instant at) {
    return NotificationEvent.<LibraryKind, LibraryFacts>builder()
        .kind(kind)
        .subjectId(MOVIES)
        .facts(facts)
        .recipients(SERVER_ADMINS)
        .actingAccountId(Optional.empty())
        .at(at)
        .build();
  }

  @Getter
  @RequiredArgsConstructor
  private enum LibraryKind {
    SCAN_OR_REFRESH_FINISHED(NotificationForm.NEWS),
    UNMATCHED_ITEMS(NotificationForm.NEWS);

    private final NotificationForm form;
  }

  private sealed interface LibraryFacts permits ScanFigures, UnmatchedItems {}

  /** The figures of every finished scan and refresh since the news was made, and the latest end. */
  private record ScanFigures(
      int titlesAdded, int titlesRemoved, int filesSkipped, Instant latestFinishedAt)
      implements LibraryFacts {

    ScanFigures plus(ScanFigures later) {
      return new ScanFigures(
          titlesAdded + later.titlesAdded,
          titlesRemoved + later.titlesRemoved,
          filesSkipped + later.filesSkipped,
          later.latestFinishedAt);
    }
  }

  /** How many unmatched items the scans added since the news was made. */
  private record UnmatchedItems(int added) implements LibraryFacts {}

  private static final class LibraryKinds implements NotificationKinds<LibraryKind, LibraryFacts> {

    @Override
    public NotificationForm formOf(LibraryKind kind) {
      return kind.getForm();
    }

    @Override
    public LibraryFacts combine(LibraryKind kind, LibraryFacts stored, LibraryFacts later) {
      return switch (later) {
        case ScanFigures figures when stored instanceof ScanFigures earlier ->
            earlier.plus(figures);
        case UnmatchedItems(var added) when stored instanceof UnmatchedItems(var earlier) ->
            new UnmatchedItems(earlier + added);
        default -> throw new IllegalArgumentException("A kind's facts combine with their own.");
      };
    }
  }

  @Getter
  @RequiredArgsConstructor
  private enum InvitationKind {
    RECEIVED(NotificationForm.REQUEST),
    ANSWERED(NotificationForm.NEWS);

    private final NotificationForm form;
  }

  private sealed interface InvitationFacts permits InvitationWaiting, InvitationAnswer {}

  /** Nothing beyond the invitation, which the request reads its state from. */
  private record InvitationWaiting() implements InvitationFacts {}

  private record InvitationAnswer(boolean accepted) implements InvitationFacts {}

  // An invitation has one answer, so its news never updates in place; a later event replaces the
  // facts as the sharing kinds' do.
  private static final class InvitationKinds
      implements NotificationKinds<InvitationKind, InvitationFacts> {

    @Override
    public NotificationForm formOf(InvitationKind kind) {
      return kind.getForm();
    }

    @Override
    public InvitationFacts combine(
        InvitationKind kind, InvitationFacts stored, InvitationFacts later) {
      return later;
    }
  }
}
