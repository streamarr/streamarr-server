package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.services.auth.events.ProfileShareChangedEvent;
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
