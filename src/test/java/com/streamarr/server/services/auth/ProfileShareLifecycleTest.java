package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.services.auth.events.ProfileShareChangedEvent;
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

    // An offer past its expiry that the sweep has not reached is announced as it is stored.
    var lapsed =
        shares.save(
            pending(UUID.randomUUID()).profileId(profileId).expiresAt(NOW.minusSeconds(1)).build());
    expected.put(ChangedShare.of(lapsed), true);

    lifecycle.announceDeletionWithProfile(profileId);

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
    var left = shares.save(pending(UUID.randomUUID()).status(ProfileShareStatus.ACTIVE).build());
    var ended =
        shares.save(
            pending(UUID.randomUUID())
                .profileId(left.getProfileId())
                .status(ProfileShareStatus.ENDED)
                .build());

    lifecycle.announceDeletionWithHousehold(offer.getHouseholdId());
    lifecycle.announceMove(left.getProfileId(), left.getHouseholdId(), ended.getHouseholdId());

    assertThat(liveness())
        .containsOnly(
            Map.entry(ChangedShare.of(offer), true),
            Map.entry(ChangedShare.of(rejected), false),
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
            (lifecycle, share) -> lifecycle.tryActivatePending(share.getId(), NOW)),
        write(
            "decline",
            ProfileShareStatus.PENDING,
            (lifecycle, share) ->
                lifecycle.tryDeclinePending(share.getId(), ProfileShareStatus.REJECTED, NOW)),
        write(
            "invalidate",
            ProfileShareStatus.PENDING,
            (lifecycle, share) -> lifecycle.tryInvalidatePending(share.getId(), REASON, NOW)),
        write(
            "end",
            ProfileShareStatus.ACTIVE,
            (lifecycle, share) -> lifecycle.tryEndActive(share.getId(), NOW)),
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

    lifecycle.announceDeletionWithProfile(offer.getProfileId());

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

    lifecycle.announceDeletionWithHousehold(offer.getHouseholdId());

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

  private Map<ChangedShare, Boolean> liveness() {
    return events.getEventsOfType(ProfileShareChangedEvent.class).stream()
        .collect(Collectors.toMap(ProfileShareChangedEvent::share, ProfileShareChangedEvent::live));
  }

  private List<ChangedShare> announced() {
    return events.getEventsOfType(ProfileShareChangedEvent.class).stream()
        .map(ProfileShareChangedEvent::share)
        .toList();
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
