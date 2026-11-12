package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ChangedManagerInvitation;
import com.streamarr.server.domain.auth.ManagerInvitationAction;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.services.events.auth.ProfileManagerInvitationChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Profile Manager Invitation Lifecycle Tests")
class ProfileManagerInvitationLifecycleTest {

  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
  private static final String REASON = "inviting manager lost management";
  private static final UUID ACTOR = UUID.randomUUID();

  private final FakeProfileManagerInvitationRepository invitations =
      new FakeProfileManagerInvitationRepository();
  private final CapturingEventPublisher events = new CapturingEventPublisher();
  private final ProfileManagerInvitationLifecycle lifecycle =
      new ProfileManagerInvitationLifecycle(invitations, events);

  @Test
  @DisplayName("Should announce each invitation that stops waiting when its Profile is deleted")
  void shouldAnnounceEachInvitationThatStopsWaitingWhenItsProfileIsDeleted() {
    var profileId = UUID.randomUUID();
    var first = invitations.save(pending().profileId(profileId).build());
    var second = invitations.save(pending().profileId(profileId).build());
    invitations.save(pending().build());

    lifecycle.invalidatePendingByProfileId(profileId, "Profile deleted", NOW);

    assertThat(announced())
        .containsExactlyInAnyOrder(
            new ProfileManagerInvitationChangedEvent(
                ChangedManagerInvitation.of(first),
                new ManagerInvitationAction.StoppedWaiting(),
                NOW),
            new ProfileManagerInvitationChangedEvent(
                ChangedManagerInvitation.of(second),
                new ManagerInvitationAction.StoppedWaiting(),
                NOW));
  }

  @Test
  @DisplayName("Should announce the new invitation when a manager issues one")
  void shouldAnnounceTheNewInvitationWhenAManagerIssuesOne() {
    var issued = lifecycle.issue(pending().build(), NOW);

    assertThat(announced())
        .containsExactly(
            new ProfileManagerInvitationChangedEvent(
                ChangedManagerInvitation.of(issued), new ManagerInvitationAction.Issued(), NOW));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("timedWrites")
  @DisplayName(
      "Should announce the instant its write took and the expiry when a write changes an"
          + " invitation")
  void shouldAnnounceTheInstantItsWriteTookAndTheExpiryWhenAWriteChangesAnInvitation(
      String write,
      Instant expiresAt,
      BiConsumer<ProfileManagerInvitationLifecycle, ProfileManagerInvitation> change) {
    var invitation = invitations.save(pending().expiresAt(expiresAt).build());

    change.accept(lifecycle, invitation);

    assertThat(announced())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.changedAt()).isEqualTo(NOW);
              assertThat(event.invitation().expiresAt()).isEqualTo(expiresAt);
            });
  }

  // Every write at NOW: each that changes one invitation, a new invitation issued like the arranged
  // one, a newer invitation to its address, and the sweep of one whose time ran out.
  static Stream<Arguments> timedWrites() {
    var live = NOW.plusSeconds(3600);
    return Stream.concat(
        singleInvitationWrites().map(write -> Arguments.of(write.get()[0], live, write.get()[3])),
        Stream.of(
            timedWrite(
                "issue",
                live,
                (lifecycle, invitation) ->
                    lifecycle.issue(pending().expiresAt(invitation.getExpiresAt()).build(), NOW)),
            timedWrite(
                "replace by a newer invitation to the address",
                live,
                (lifecycle, invitation) ->
                    lifecycle.invalidatePendingByProfileIdAndRecipientEmail(
                        invitation.getProfileId(), invitation.getRecipientEmail(), REASON, NOW)),
            timedWrite(
                "expire one whose time ran out",
                NOW.minusSeconds(1),
                (lifecycle, _) -> lifecycle.expireLapsed(NOW))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("singleInvitationWrites")
  @DisplayName("Should announce the invitation and who acted when one write changes it")
  void shouldAnnounceTheInvitationAndWhoActedWhenOneWriteChangesIt(
      String write,
      ProfileManagerInvitationStatus after,
      ManagerInvitationAction action,
      BiConsumer<ProfileManagerInvitationLifecycle, ProfileManagerInvitation> change) {
    var invitation = invitations.save(pending().build());

    change.accept(lifecycle, invitation);

    assertThat(announced())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.invitation().invitationId()).isEqualTo(invitation.getId());
              assertThat(event.invitation().status()).isEqualTo(after);
              assertThat(event.action()).isEqualTo(action);
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("singleInvitationWrites")
  @DisplayName("Should announce nothing when a write finds no pending invitation to change")
  void shouldAnnounceNothingWhenAWriteFindsNoPendingInvitationToChange(
      String write,
      ProfileManagerInvitationStatus after,
      ManagerInvitationAction action,
      BiConsumer<ProfileManagerInvitationLifecycle, ProfileManagerInvitation> change) {
    var invitation =
        invitations.save(pending().status(ProfileManagerInvitationStatus.DECLINED).build());

    change.accept(lifecycle, invitation);

    assertThat(announced()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should announce the expiry when an invalidation for its Account finds the invitation lapsed")
  void shouldAnnounceTheExpiryWhenAnInvalidationForItsAccountFindsTheInvitationLapsed() {
    var lapsed = invitations.save(pending().expiresAt(NOW.minusSeconds(1)).build());

    lifecycle.invalidatePendingByProfileIdAndRecipientAccountId(
        lapsed.getProfileId(), lapsed.getRecipientAccountId(), REASON, NOW);

    assertThat(announced())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.invitation().status())
                  .isEqualTo(ProfileManagerInvitationStatus.EXPIRED);
              assertThat(event.action()).isEqualTo(new ManagerInvitationAction.StoppedWaiting());
            });
  }

  static Stream<Arguments> singleInvitationWrites() {
    var stoppedWaiting = new ManagerInvitationAction.StoppedWaiting();
    var invalidated = ProfileManagerInvitationStatus.INVALIDATED;
    return Stream.of(
        write(
            "accept",
            ProfileManagerInvitationStatus.ACCEPTED,
            new ManagerInvitationAction.Answered(ACTOR),
            (lifecycle, invitation) -> lifecycle.tryAcceptPending(invitation.getId(), ACTOR, NOW)),
        write(
            "decline",
            ProfileManagerInvitationStatus.DECLINED,
            new ManagerInvitationAction.Answered(ACTOR),
            (lifecycle, invitation) -> lifecycle.tryDeclinePending(invitation.getId(), ACTOR, NOW)),
        write(
            "cancel",
            ProfileManagerInvitationStatus.CANCELED,
            new ManagerInvitationAction.Canceled(ACTOR),
            (lifecycle, invitation) -> lifecycle.tryCancelPending(invitation.getId(), ACTOR, NOW)),
        write(
            "invalidate",
            invalidated,
            stoppedWaiting,
            (lifecycle, invitation) ->
                lifecycle.tryInvalidatePending(invitation.getId(), REASON, NOW)),
        write(
            "invalidate for an Account that becomes or stops being a manager",
            ProfileManagerInvitationStatus.PENDING,
            new ManagerInvitationAction.InvalidatedForItsAccount(),
            (lifecycle, invitation) ->
                lifecycle.invalidatePendingByProfileIdAndRecipientAccountId(
                    invitation.getProfileId(), invitation.getRecipientAccountId(), REASON, NOW)),
        write(
            "invalidate the Profile's invitations",
            invalidated,
            stoppedWaiting,
            (lifecycle, invitation) ->
                lifecycle.invalidatePendingByProfileId(invitation.getProfileId(), REASON, NOW)),
        write(
            "invalidate the leaving manager's invitations",
            invalidated,
            stoppedWaiting,
            (lifecycle, invitation) ->
                lifecycle.invalidatePendingInvitationsByInviterAccountIdAndProfileId(
                    invitation.getInviterAccountId(), invitation.getProfileId(), REASON, NOW)),
        write(
            "invalidate the deleted inviter's invitations",
            invalidated,
            stoppedWaiting,
            (lifecycle, invitation) ->
                lifecycle.invalidatePendingInvitedBy(
                    invitation.getInviterAccountId(), REASON, NOW)),
        write(
            "invalidate for an Account that is deleted or becomes ineligible",
            ProfileManagerInvitationStatus.PENDING,
            new ManagerInvitationAction.InvalidatedForItsAccount(),
            (lifecycle, invitation) ->
                lifecycle.invalidatePendingByRecipientAccountId(
                    invitation.getRecipientAccountId(), REASON, NOW)));
  }

  private static Arguments timedWrite(
      String name,
      Instant expiresAt,
      BiConsumer<ProfileManagerInvitationLifecycle, ProfileManagerInvitation> change) {
    return Arguments.of(name, expiresAt, change);
  }

  private static Arguments write(
      String name,
      ProfileManagerInvitationStatus after,
      ManagerInvitationAction action,
      BiConsumer<ProfileManagerInvitationLifecycle, ProfileManagerInvitation> change) {
    return Arguments.of(name, after, action, change);
  }

  private List<ProfileManagerInvitationChangedEvent> announced() {
    return events.getEventsOfType(ProfileManagerInvitationChangedEvent.class);
  }

  private static ProfileManagerInvitation.ProfileManagerInvitationBuilder<?, ?> pending() {
    return ProfileManagerInvitation.builder()
        .profileId(UUID.randomUUID())
        .profileName("Alex")
        .inviterAccountId(UUID.randomUUID())
        .inviterDisplayName("Sam")
        .recipientAccountId(UUID.randomUUID())
        .recipientEmail("mara@example.com")
        .expiresAt(NOW.plusSeconds(3600))
        .publicId(UUID.randomUUID().toString())
        .secretDigest(new byte[] {1});
  }
}
