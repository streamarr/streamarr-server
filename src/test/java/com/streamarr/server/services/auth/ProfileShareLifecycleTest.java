package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareAction;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.services.events.auth.ProfileShareChangedEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Profile Share Lifecycle Tests")
class ProfileShareLifecycleTest {

  private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
  private static final String REASON = "offerer lost authority";
  private static final UUID ACTOR = UUID.randomUUID();

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final CapturingEventPublisher events = new CapturingEventPublisher();
  private final ProfileShareLifecycle lifecycle = new ProfileShareLifecycle(shares, events);

  @Test
  @DisplayName("Should announce the new share when a share is created")
  void shouldAnnounceTheNewShareWhenAShareIsCreated() {
    var created = lifecycle.create(pending(UUID.randomUUID()).build());

    assertThat(announced()).containsExactly(ChangedShare.of(created));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("singleShareWrites")
  @DisplayName("Should announce the share when one write changes it")
  void shouldAnnounceTheShareWhenOneWriteChangesIt(
      String write,
      ProfileShareStatus before,
      BiConsumer<ProfileShareLifecycle, ProfileHouseholdShare> change) {
    var share = shares.save(pending(UUID.randomUUID()).status(before).build());

    change.accept(lifecycle, share);

    assertThat(announced()).containsExactly(ChangedShare.of(share));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("singleShareWrites")
  @DisplayName("Should announce the change of a live share when one write changes it")
  void shouldAnnounceTheChangeOfALiveShareWhenOneWriteChangesIt(
      String write,
      ProfileShareStatus before,
      BiConsumer<ProfileShareLifecycle, ProfileHouseholdShare> change) {
    var share = shares.save(pending(UUID.randomUUID()).status(before).build());

    change.accept(lifecycle, share);

    // The share was pending or active until this change, so its Household hears this one.
    assertThat(liveness()).containsExactly(Map.entry(ChangedShare.of(share), true));
  }

  @Test
  @DisplayName(
      "Should announce the change of a live share when a share is created or an offer expires")
  void shouldAnnounceTheChangeOfALiveShareWhenAShareIsCreatedOrAnOfferExpires() {
    var created = lifecycle.create(pending(UUID.randomUUID()).build());
    var lapsed = shares.save(pending(UUID.randomUUID()).expiresAt(NOW.minusSeconds(1)).build());

    lifecycle.expireLapsedOffers(NOW);

    assertThat(liveness())
        .containsOnly(
            Map.entry(ChangedShare.of(created), true), Map.entry(ChangedShare.of(lapsed), true));
  }

  @Test
  @DisplayName(
      "Should announce only the pending and active shares as live when the Profile is about to be"
          + " deleted")
  void shouldAnnounceOnlyPendingAndActiveSharesAsLiveWhenTheProfileIsAboutToBeDeleted() {
    var profileId = UUID.randomUUID();
    var expected = new HashMap<ChangedShare, Boolean>();
    for (var status : ProfileShareStatus.values()) {
      var share =
          shares.save(pending(UUID.randomUUID()).profileId(profileId).status(status).build());
      expected.put(ChangedShare.of(share), status.isLive());
    }

    // An offer past its expiry that the sweep has not reached is announced as the change that ends
    // it, the expiry the sweep would have stored.
    var lapsed =
        shares.save(
            pending(UUID.randomUUID()).profileId(profileId).expiresAt(NOW.minusSeconds(1)).build());
    expected.put(expiredAs(lapsed), true);

    lifecycle.announceDeletionWithProfile(profileId, NOW);

    assertThat(liveness()).containsExactlyInAnyOrderEntriesOf(expected);
  }

  @Test
  @DisplayName(
      "Should announce only the pending and active shares as live when the Household is about to"
          + " be deleted or a Profile moves")
  void
      shouldAnnounceOnlyPendingAndActiveSharesAsLiveWhenHouseholdIsAboutToBeDeletedOrProfileMoves() {
    var offer = shares.save(pending(UUID.randomUUID()).build());
    var rejected =
        shares.save(
            pending(UUID.randomUUID())
                .householdId(offer.getHouseholdId())
                .status(ProfileShareStatus.REJECTED)
                .build());
    var lapsed =
        shares.save(
            pending(UUID.randomUUID())
                .householdId(offer.getHouseholdId())
                .expiresAt(NOW.minusSeconds(1))
                .build());
    var left = shares.save(pending(UUID.randomUUID()).status(ProfileShareStatus.ACTIVE).build());
    var ended =
        shares.save(
            pending(UUID.randomUUID())
                .profileId(left.getProfileId())
                .status(ProfileShareStatus.ENDED)
                .build());

    lifecycle.announceDeletionWithHousehold(offer.getHouseholdId(), NOW);
    lifecycle.announceMove(left.getProfileId(), left.getHouseholdId(), ended.getHouseholdId());

    assertThat(liveness())
        .containsOnly(
            Map.entry(ChangedShare.of(offer), true),
            Map.entry(ChangedShare.of(rejected), false),
            Map.entry(expiredAs(lapsed), true),
            Map.entry(ChangedShare.of(left), true),
            Map.entry(ChangedShare.of(ended), false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("singleShareWrites")
  @DisplayName("Should announce nothing when a write finds no share to change")
  void shouldAnnounceNothingWhenAWriteFindsNoShareToChange(
      String write,
      ProfileShareStatus before,
      BiConsumer<ProfileShareLifecycle, ProfileHouseholdShare> change) {
    var share = shares.save(pending(UUID.randomUUID()).status(ProfileShareStatus.ENDED).build());

    change.accept(lifecycle, share);

    assertThat(announced()).isEmpty();
  }

  static Stream<Arguments> singleShareWrites() {
    return Stream.of(
        write(
            "accept",
            ProfileShareStatus.PENDING,
            (lifecycle, share) -> lifecycle.tryActivatePending(share.getId(), ACTOR, NOW)),
        write(
            "reject",
            ProfileShareStatus.PENDING,
            (lifecycle, share) -> lifecycle.tryRejectPending(share.getId(), ACTOR, NOW)),
        write(
            "cancel",
            ProfileShareStatus.PENDING,
            (lifecycle, share) -> lifecycle.tryCancelPending(share.getId(), NOW)),
        write(
            "invalidate",
            ProfileShareStatus.PENDING,
            (lifecycle, share) -> lifecycle.tryInvalidatePending(share.getId(), REASON, NOW)),
        write(
            "end",
            ProfileShareStatus.ACTIVE,
            (lifecycle, share) ->
                lifecycle.tryEndActive(share.getId(), new ShareAction.EndedBySide(ACTOR), NOW)),
        write(
            "supersede",
            ProfileShareStatus.PENDING,
            (lifecycle, share) ->
                lifecycle.supersedePending(share.getProfileId(), share.getHouseholdId(), NOW)),
        write(
            "invalidate the Profile's offers",
            ProfileShareStatus.PENDING,
            (lifecycle, share) ->
                lifecycle.invalidatePendingByProfileId(share.getProfileId(), REASON, NOW)),
        write(
            "invalidate the offers into the Household",
            ProfileShareStatus.PENDING,
            (lifecycle, share) ->
                lifecycle.invalidatePendingByHouseholdId(share.getHouseholdId(), REASON, NOW)),
        write(
            "invalidate the leaving manager's offers",
            ProfileShareStatus.PENDING,
            (lifecycle, share) ->
                lifecycle.invalidatePendingOffersByProfileIdAndOffererAccountId(
                    share.getProfileId(), share.getOfferedByAccountId(), REASON, NOW)));
  }

  private static Arguments write(
      String write,
      ProfileShareStatus before,
      BiConsumer<ProfileShareLifecycle, ProfileHouseholdShare> change) {
    return Arguments.of(write, before, change);
  }

  @Test
  @DisplayName("Should announce every offer the Account made when its offers are withdrawn")
  void shouldAnnounceEveryOfferTheAccountMadeWhenItsOffersAreWithdrawn() {
    var offerer = UUID.randomUUID();
    var first = shares.save(pending(offerer).build());
    var second = shares.save(pending(offerer).build());
    shares.save(pending(UUID.randomUUID()).build());

    var withdrawn = lifecycle.invalidatePendingOfferedBy(offerer, REASON, NOW);

    assertThat(withdrawn).isEqualTo(2);
    assertThat(announced())
        .containsExactlyInAnyOrder(ChangedShare.of(first), ChangedShare.of(second));
  }

  @Test
  @DisplayName(
      "Should announce the lapsed offer and the membership share when a Profile is linked home")
  void shouldAnnounceTheLapsedOfferAndTheMembershipShareWhenAProfileIsLinkedHome() {
    var lapsed = shares.save(pending(UUID.randomUUID()).expiresAt(NOW.minusSeconds(1)).build());

    lifecycle.ensureActiveMembershipShare(lapsed.getProfileId(), lapsed.getHouseholdId(), NOW);

    var membership =
        shares
            .findByProfileIdAndHouseholdIdAndStatus(
                lapsed.getProfileId(), lapsed.getHouseholdId(), ProfileShareStatus.ACTIVE)
            .orElseThrow();
    assertThat(announced())
        .containsExactlyInAnyOrder(ChangedShare.of(lapsed), ChangedShare.of(membership));
  }

  @Test
  @DisplayName("Should announce the share when it stops being required by Account membership")
  void shouldAnnounceTheShareWhenItStopsBeingRequiredByAccountMembership() {
    var membership = shares.share(UUID.randomUUID(), UUID.randomUUID(), true);

    lifecycle.convertMembershipShareToVisitorShare(
        membership.getProfileId(), membership.getHouseholdId());

    assertThat(announced()).containsExactly(ChangedShare.of(membership));
  }

  @Test
  @DisplayName("Should announce every lapsed offer when the offers whose time ran out are expired")
  void shouldAnnounceEveryLapsedOfferWhenTheOffersWhoseTimeRanOutAreExpired() {
    var lapsed = shares.save(pending(UUID.randomUUID()).expiresAt(NOW.minusSeconds(1)).build());
    var live = shares.save(pending(UUID.randomUUID()).build());

    var expired = lifecycle.expireLapsedOffers(NOW);

    assertThat(expired).isEqualTo(1);
    assertThat(announced()).containsExactly(ChangedShare.of(lapsed));
    assertThat(shares.findById(live.getId()))
        .hasValueSatisfying(
            share -> assertThat(share.getStatus()).isEqualTo(ProfileShareStatus.PENDING));
  }

  @Test
  @DisplayName("Should announce every share of the Profile when the Profile is about to be deleted")
  void shouldAnnounceEveryShareOfTheProfileWhenTheProfileIsAboutToBeDeleted() {
    var offer = shares.save(pending(UUID.randomUUID()).build());
    var ended =
        shares.save(
            pending(UUID.randomUUID())
                .profileId(offer.getProfileId())
                .status(ProfileShareStatus.ENDED)
                .build());
    shares.save(pending(UUID.randomUUID()).build());

    lifecycle.announceDeletionWithProfile(offer.getProfileId(), NOW);

    assertThat(announced())
        .containsExactlyInAnyOrder(ChangedShare.of(offer), ChangedShare.of(ended));
  }

  @Test
  @DisplayName(
      "Should announce every share into the Household when the Household is about to be deleted")
  void shouldAnnounceEveryShareIntoTheHouseholdWhenTheHouseholdIsAboutToBeDeleted() {
    var offer = shares.save(pending(UUID.randomUUID()).build());
    var rejected =
        shares.save(
            pending(UUID.randomUUID())
                .householdId(offer.getHouseholdId())
                .status(ProfileShareStatus.REJECTED)
                .build());
    shares.save(pending(UUID.randomUUID()).build());

    lifecycle.announceDeletionWithHousehold(offer.getHouseholdId(), NOW);

    assertThat(announced())
        .containsExactlyInAnyOrder(ChangedShare.of(offer), ChangedShare.of(rejected));
  }

  @Test
  @DisplayName(
      "Should announce the Profile's shares into both Households when the Profile moves between"
          + " them")
  void shouldAnnounceTheProfilesSharesIntoBothHouseholdsWhenTheProfileMovesBetweenThem() {
    var left = shares.save(pending(UUID.randomUUID()).status(ProfileShareStatus.ACTIVE).build());
    var profileId = left.getProfileId();
    var joined =
        shares.save(
            pending(UUID.randomUUID())
                .profileId(profileId)
                .status(ProfileShareStatus.REJECTED)
                .build());
    shares.save(pending(UUID.randomUUID()).profileId(profileId).build());
    shares.save(pending(UUID.randomUUID()).householdId(joined.getHouseholdId()).build());

    lifecycle.announceMove(profileId, left.getHouseholdId(), joined.getHouseholdId());

    assertThat(announced())
        .containsExactlyInAnyOrder(ChangedShare.of(left), ChangedShare.of(joined));
  }

  @Test
  @DisplayName("Should announce an offer when a pending share is created")
  void shouldAnnounceAnOfferWhenAPendingShareIsCreated() {
    lifecycle.create(pending(UUID.randomUUID()).build());

    assertThat(actions()).containsExactly(new ShareAction.Offered());
  }

  @Test
  @DisplayName("Should announce no action to tell when an active share is created")
  void shouldAnnounceNoActionToTellWhenAnActiveShareIsCreated() {
    lifecycle.create(pending(UUID.randomUUID()).status(ProfileShareStatus.ACTIVE).build());

    assertThat(actions()).containsExactly(new ShareAction.Other());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("answers")
  @DisplayName("Should announce the Account that answered when an offer is accepted or rejected")
  void shouldAnnounceTheAccountThatAnsweredWhenAnOfferIsAcceptedOrRejected(
      String answer,
      ProfileShareStatus before,
      BiConsumer<ProfileShareLifecycle, ProfileHouseholdShare> change) {
    var share = shares.save(pending(UUID.randomUUID()).status(before).build());

    change.accept(lifecycle, share);

    assertThat(actions()).containsExactly(new ShareAction.Answered(ACTOR));
  }

  static Stream<Arguments> answers() {
    return Stream.of(
        write(
            "accept",
            ProfileShareStatus.PENDING,
            (lifecycle, share) -> lifecycle.tryActivatePending(share.getId(), ACTOR, NOW)),
        write(
            "reject",
            ProfileShareStatus.PENDING,
            (lifecycle, share) -> lifecycle.tryRejectPending(share.getId(), ACTOR, NOW)));
  }

  @ParameterizedTest
  @MethodSource("endings")
  @DisplayName("Should announce how the share ended when an active share ends")
  void shouldAnnounceHowTheShareEndedWhenAnActiveShareEnds(ShareAction.Ending ending) {
    var share = shares.save(pending(UUID.randomUUID()).status(ProfileShareStatus.ACTIVE).build());

    lifecycle.tryEndActive(share.getId(), ending, NOW);

    assertThat(actions()).containsExactly(ending);
  }

  static Stream<ShareAction.Ending> endings() {
    return Stream.of(
        new ShareAction.EndedBySide(ACTOR),
        new ShareAction.EndedByServer(ACTOR),
        new ShareAction.EndedWithHousehold());
  }

  @Test
  @DisplayName(
      "Should announce that the offer stopped waiting when an offer is canceled, withdrawn or"
          + " expires")
  void shouldAnnounceThatTheOfferStoppedWaitingWhenAnOfferIsCanceledWithdrawnOrExpires() {
    var offerer = UUID.randomUUID();
    var canceled = shares.save(pending(UUID.randomUUID()).build());
    var invalidated = shares.save(pending(UUID.randomUUID()).build());
    var replaced = shares.save(pending(UUID.randomUUID()).build());
    var ofTheProfile = shares.save(pending(UUID.randomUUID()).build());
    var ofTheLeavingManager = shares.save(pending(UUID.randomUUID()).build());
    var intoTheHousehold = shares.save(pending(UUID.randomUUID()).build());
    shares.save(pending(offerer).build());
    shares.save(pending(UUID.randomUUID()).expiresAt(NOW.minusSeconds(1)).build());

    lifecycle.tryCancelPending(canceled.getId(), NOW);
    lifecycle.tryInvalidatePending(invalidated.getId(), REASON, NOW);
    lifecycle.supersedePending(replaced.getProfileId(), replaced.getHouseholdId(), NOW);
    lifecycle.invalidatePendingByProfileId(ofTheProfile.getProfileId(), REASON, NOW);
    lifecycle.invalidatePendingByHouseholdId(intoTheHousehold.getHouseholdId(), REASON, NOW);
    lifecycle.invalidatePendingOffersByProfileIdAndOffererAccountId(
        ofTheLeavingManager.getProfileId(),
        ofTheLeavingManager.getOfferedByAccountId(),
        REASON,
        NOW);
    lifecycle.invalidatePendingOfferedBy(offerer, REASON, NOW);
    lifecycle.expireLapsedOffers(NOW);

    assertThat(actions()).hasSize(8).containsOnly(new ShareAction.OfferStoppedWaiting());
  }

  @Test
  @DisplayName(
      "Should announce once that the offer stopped waiting when a Profile is linked home while its"
          + " offer into that Household waits")
  void shouldAnnounceOnceThatTheOfferStoppedWaitingWhenAProfileIsLinkedHomeWhileItsOfferWaits() {
    var waiting = shares.save(pending(UUID.randomUUID()).build());

    lifecycle.ensureActiveMembershipShare(waiting.getProfileId(), waiting.getHouseholdId(), NOW);

    assertThat(actionsByShare())
        .containsExactly(
            Map.entry(ChangedShare.of(waiting), List.of(new ShareAction.OfferStoppedWaiting())));
    assertThat(shares.findById(waiting.getId()))
        .hasValueSatisfying(
            share -> assertThat(share.getStatus()).isEqualTo(ProfileShareStatus.ACTIVE));
  }

  @Test
  @DisplayName(
      "Should announce that the lapsed offer stopped waiting and no action to tell for the"
          + " membership share when a Profile is linked home")
  void shouldAnnounceThatTheLapsedOfferStoppedWaitingWhenAProfileIsLinkedHome() {
    var lapsed = shares.save(pending(UUID.randomUUID()).expiresAt(NOW.minusSeconds(1)).build());

    lifecycle.ensureActiveMembershipShare(lapsed.getProfileId(), lapsed.getHouseholdId(), NOW);

    var membership =
        shares
            .findByProfileIdAndHouseholdIdAndStatus(
                lapsed.getProfileId(), lapsed.getHouseholdId(), ProfileShareStatus.ACTIVE)
            .orElseThrow();
    assertThat(actionsByShare())
        .containsOnly(
            Map.entry(ChangedShare.of(lapsed), List.of(new ShareAction.OfferStoppedWaiting())),
            Map.entry(ChangedShare.of(membership), List.of(new ShareAction.Other())));
  }

  private Map<ChangedShare, List<ShareAction>> actionsByShare() {
    return events.getEventsOfType(ProfileShareChangedEvent.class).stream()
        .collect(
            Collectors.groupingBy(
                ProfileShareChangedEvent::share,
                Collectors.mapping(ProfileShareChangedEvent::action, Collectors.toList())));
  }

  private List<ShareAction> actions() {
    return events.getEventsOfType(ProfileShareChangedEvent.class).stream()
        .map(ProfileShareChangedEvent::action)
        .toList();
  }

  private Map<ChangedShare, Boolean> liveness() {
    return events.getEventsOfType(ProfileShareChangedEvent.class).stream()
        .collect(Collectors.toMap(ProfileShareChangedEvent::share, ProfileShareChangedEvent::live));
  }

  private List<ChangedShare> announced() {
    return events.getEventsOfType(ProfileShareChangedEvent.class).stream()
        .map(ProfileShareChangedEvent::share)
        .toList();
  }

  private static ChangedShare expiredAs(ProfileHouseholdShare lapsed) {
    return new ChangedShare(
        lapsed.getId(), lapsed.getProfileId(), lapsed.getHouseholdId(), ProfileShareStatus.EXPIRED);
  }

  private static ProfileHouseholdShare.ProfileHouseholdShareBuilder<?, ?> pending(UUID offerer) {
    return ProfileHouseholdShare.builder()
        .profileId(UUID.randomUUID())
        .householdId(UUID.randomUUID())
        .status(ProfileShareStatus.PENDING)
        .offeredByAccountId(offerer)
        .expiresAt(NOW.plusSeconds(3600));
  }
}
