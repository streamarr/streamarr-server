package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Profile Share Parties Service Tests")
class ProfileSharePartiesServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());
  private final ProfileSharePartiesService service =
      new ProfileSharePartiesService(authorization, profiles, accounts, households);

  private Household target;
  private Profile offered;
  private UserAccount offerer;
  private ShareReference share;

  @BeforeEach
  void setUp() {
    target = households.save(HouseholdFixture.defaultHouseholdBuilder().name("Lakeside").build());
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    offered =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(home.getId())
                .name("Sam")
                .picture("fox")
                .build());
    offerer = accounts.save(AccountFixture.defaultAccountBuilder().displayName("Robin").build());
    share = liveShare(offered.getId(), target.getId(), Optional.of(offerer.getId()));
  }

  // ---- The Profile on a share.

  @Test
  @DisplayName("Should show the Profile when the reader administers the target Household")
  void shouldShowProfileWhenReaderAdministersTargetHousehold() {
    allowOnly(this::administersTarget);

    var shown = service.profiles(reader(), Set.of(share)).get(share);

    assertThat(shown.profile().getName()).isEqualTo("Sam");
    assertThat(shown.profile().getPicture()).isEqualTo("fox");
    assertThat(shown.personalProfile()).isFalse();
  }

  @Test
  @DisplayName("Should show the Profile when the reader is on the offering side")
  void shouldShowProfileWhenReaderIsOnOfferingSide() {
    allowOnly(this::offeringSide);

    assertThat(service.profiles(reader(), Set.of(share))).containsKey(share);
  }

  @Test
  @DisplayName("Should hide the Profile when the reader only hosts it in another Household")
  void shouldHideProfileWhenReaderOnlyHostsItInAnotherHousehold() {
    // Seeing the Profile's administration where it is available names nothing on this share.
    allowOnly(intent -> intent.equals(new Intent.ViewProfileAdministration(offered.getId())));

    assertThat(service.profiles(reader(), Set.of(share))).isEmpty();
  }

  // ---- Each status, on each side (ADR 0024 §Profile sharing, §Read authorization).

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = ProfileShareStatus.class,
      names = {"PENDING", "ACTIVE"})
  @DisplayName(
      "Should show the target HouseholdAdmin the Profile and the offerer when the offer is pending"
          + " or the share is active")
  void shouldShowTargetHouseholdAdminProfileAndOffererWhenOfferIsPendingOrShareIsActive(
      ProfileShareStatus status) {
    allowOnly(this::administersTarget);
    var live = shareIn(status);

    assertThat(service.profiles(reader(), Set.of(live))).containsKey(live);
    assertThat(service.offererNames(reader(), Set.of(live))).containsEntry(live, "Robin");
    assertThat(service.households(reader(), Set.of(live))).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = ProfileShareStatus.class,
      names = {"REJECTED", "CANCELED", "EXPIRED", "INVALIDATED", "ENDED"})
  @DisplayName(
      "Should hide the Profile and the offerer from the target HouseholdAdmin when the offer or"
          + " the share is over")
  void shouldHideProfileAndOffererFromTargetHouseholdAdminWhenOfferOrShareIsOver(
      ProfileShareStatus status) {
    allowOnly(this::administersTarget);
    var over = shareIn(status);

    assertThat(service.profiles(reader(), Set.of(over))).isEmpty();
    assertThat(service.offererNames(reader(), Set.of(over))).isEmpty();
    assertThat(service.households(reader(), Set.of(over))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should hide the Profile and the offerer from the target HouseholdAdmin when a pending offer"
          + " is past its expiry")
  void shouldHideProfileAndOffererFromTargetHouseholdAdminWhenPendingOfferIsPastItsExpiry() {
    allowOnly(this::administersTarget);
    var lapsed = stored(offerOf(ProfileShareStatus.PENDING).expiresAt(NOW.minusSeconds(1)));

    assertThat(service.profiles(reader(), Set.of(lapsed))).isEmpty();
    assertThat(service.offererNames(reader(), Set.of(lapsed))).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ProfileShareStatus.class)
  @DisplayName(
      "Should show the Profile and the Household but not the offerer at every status when the"
          + " reader is on the offering side")
  void shouldShowProfileAndHouseholdButNotOffererAtEveryStatusWhenReaderIsOnOfferingSide(
      ProfileShareStatus status) {
    allowOnly(this::offeringSide);
    var any = shareIn(status);

    assertThat(service.profiles(reader(), Set.of(any))).containsKey(any);
    assertThat(service.households(reader(), Set.of(any))).containsKey(any);
    assertThat(service.offererNames(reader(), Set.of(any))).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ProfileShareStatus.class)
  @DisplayName("Should show every party at every status when the reader is a ServerAdmin")
  void shouldShowEveryPartyAtEveryStatusWhenReaderIsServerAdmin(ProfileShareStatus status) {
    // A ServerAdmin is allowed each of these reads.
    allowOnly(
        intent ->
            administersTarget(intent)
                || intent.equals(new Intent.ViewHouseholdShareHistory(target.getId()))
                || offeringSide(intent));
    var any = shareIn(status);

    assertThat(service.profiles(reader(), Set.of(any))).containsKey(any);
    assertThat(service.offererNames(reader(), Set.of(any))).containsEntry(any, "Robin");
    assertThat(service.households(reader(), Set.of(any))).containsKey(any);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = ProfileShareStatus.class,
      names = {"REJECTED", "CANCELED", "EXPIRED", "INVALIDATED", "ENDED"})
  @DisplayName(
      "Should name the Profile and the offerer to a reader of the Household's share history when"
          + " the offer or the share is over")
  void shouldNameProfileAndOffererToReaderOfHouseholdsShareHistoryWhenOfferOrShareIsOver(
      ProfileShareStatus status) {
    allowOnly(intent -> intent.equals(new Intent.ViewHouseholdShareHistory(target.getId())));
    var over = shareIn(status);

    assertThat(service.offererNames(reader(), Set.of(over))).containsEntry(over, "Robin");
    assertThat(service.profiles(reader(), Set.of(over))).containsKey(over);
  }

  @Test
  @DisplayName("Should mark a Personal Profile when an Account is represented by it")
  void shouldMarkPersonalProfileWhenAccountIsRepresentedByIt() {
    allowOnly(this::administersTarget);
    var person = profiles.save(ProfileFixture.kidProfileBuilder().name("Jo").build());
    accounts.save(AccountFixture.defaultAccountBuilder().personalProfileId(person.getId()).build());
    var personShare = liveShare(person.getId(), target.getId(), Optional.empty());

    var shown = service.profiles(reader(), Set.of(share, personShare));

    assertThat(shown.get(personShare).personalProfile()).isTrue();
    assertThat(shown.get(personShare).profile().isRestricted()).isTrue();
    assertThat(shown.get(share).personalProfile()).isFalse();
    assertThat(shown.get(share).profile().isRestricted()).isFalse();
  }

  @Test
  @DisplayName("Should mark a visitor when the share's Profile belongs to another Household")
  void shouldMarkVisitorWhenSharesProfileBelongsToAnotherHousehold() {
    allowOnly(this::administersTarget);
    var own =
        profiles.save(ProfileFixture.defaultProfileBuilder().householdId(target.getId()).build());
    var ownShare = liveShare(own.getId(), target.getId(), Optional.empty());

    var shown = service.profiles(reader(), Set.of(share, ownShare));

    assertThat(offered.getHouseholdId()).isNotEqualTo(target.getId());
    assertThat(shown.get(share).visitor()).isTrue();
    assertThat(shown.get(ownShare).visitor()).isFalse();
  }

  @Test
  @DisplayName("Should answer no Profile when the share's Profile is gone")
  void shouldAnswerNoProfileWhenSharesProfileIsGone() {
    // A decision about a missing Profile cannot be made, so asking would fail.
    failOnProfileSide();
    var gone = liveShare(UUID.randomUUID(), target.getId(), Optional.of(offerer.getId()));

    assertThat(service.profiles(reader(), Set.of(gone))).isEmpty();
  }

  // ---- The Account that offered a share.

  @Test
  @DisplayName("Should name the offerer when the reader administers the target Household")
  void shouldNameOffererWhenReaderAdministersTargetHousehold() {
    allowOnly(this::administersTarget);

    assertThat(service.offererNames(reader(), Set.of(share))).containsEntry(share, "Robin");
  }

  @Test
  @DisplayName("Should not name the offerer when the reader is on the offering side only")
  void shouldNotNameOffererWhenReaderIsOnOfferingSideOnly() {
    allowOnly(
        intent ->
            intent.equals(new Intent.ViewProfileAdministration(offered.getId()))
                || intent.equals(new Intent.ViewProfileShareHouseholds(offered.getId())));

    assertThat(service.offererNames(reader(), Set.of(share))).isEmpty();
  }

  @Test
  @DisplayName("Should name no offerer when the system made the share or the offerer is gone")
  void shouldNameNoOffererWhenSystemMadeShareOrOffererIsGone() {
    allowOnly(this::administersTarget);
    var structural = liveShare(offered.getId(), target.getId(), Optional.empty());
    var orphaned = liveShare(offered.getId(), target.getId(), Optional.of(UUID.randomUUID()));

    assertThat(service.offererNames(reader(), Set.of(structural, orphaned))).isEmpty();
  }

  // ---- The Household a share is into.

  @Test
  @DisplayName("Should show the Household when the reader is on the offering side")
  void shouldShowHouseholdWhenReaderIsOnOfferingSide() {
    allowOnly(intent -> intent.equals(new Intent.ViewProfileShareHouseholds(offered.getId())));

    assertThat(service.households(reader(), Set.of(share)).get(share).getName())
        .isEqualTo("Lakeside");
  }

  @Test
  @DisplayName("Should hide the Household when the reader only administers it")
  void shouldHideHouseholdWhenReaderOnlyAdministersIt() {
    // The Household's name belongs to the Profile's side (ADR 0024).
    allowOnly(this::administersTarget);

    assertThat(service.households(reader(), Set.of(share))).isEmpty();
  }

  @Test
  @DisplayName("Should show the Household when the reader may view its share history")
  void shouldShowHouseholdWhenReaderMayViewItsShareHistory() {
    allowOnly(intent -> intent.equals(new Intent.ViewHouseholdShareHistory(target.getId())));

    assertThat(service.households(reader(), Set.of(share)).get(share).getName())
        .isEqualTo("Lakeside");
  }

  @Test
  @DisplayName("Should hide the Household when the reader only hosts the Profile elsewhere")
  void shouldHideHouseholdWhenReaderOnlyHostsProfileElsewhere() {
    allowOnly(intent -> intent.equals(new Intent.ViewProfileAdministration(offered.getId())));

    assertThat(service.households(reader(), Set.of(share))).isEmpty();
  }

  @Test
  @DisplayName("Should answer no Household from the offering side when the share's Profile is gone")
  void shouldAnswerNoHouseholdFromOfferingSideWhenSharesProfileIsGone() {
    failOnProfileSide();
    var gone = liveShare(UUID.randomUUID(), UUID.randomUUID(), Optional.empty());

    assertThat(service.households(reader(), Set.of(gone))).isEmpty();
  }

  // ---- Many shares, one reader.

  @Test
  @DisplayName("Should decide each share by its own Household and Profile when a page is mixed")
  void shouldDecideEachShareByItsOwnHouseholdAndProfileWhenPageIsMixed() {
    var elsewhere = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var other = profiles.save(ProfileFixture.defaultProfileBuilder().build());
    var intoElsewhere = liveShare(offered.getId(), elsewhere.getId(), Optional.of(offerer.getId()));
    var otherIntoTarget = liveShare(other.getId(), target.getId(), Optional.of(offerer.getId()));
    allowOnly(
        intent ->
            administersTarget(intent)
                || intent.equals(new Intent.ViewProfileShareHouseholds(other.getId())));

    var names = service.offererNames(reader(), Set.of(share, intoElsewhere, otherIntoTarget));
    var shown = service.households(reader(), Set.of(share, intoElsewhere, otherIntoTarget));

    assertThat(names).containsOnlyKeys(share, otherIntoTarget);
    assertThat(shown).containsOnlyKeys(otherIntoTarget);
  }

  @Test
  @DisplayName("Should fail closed when the reader's authority cannot be decided")
  void shouldFailClosedWhenReadersAuthorityCannotBeDecided() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var reader = reader();
    var shown = Set.of(share);

    assertThatThrownBy(() -> service.profiles(reader, shown))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThatThrownBy(() -> service.offererNames(reader, shown))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThatThrownBy(() -> service.households(reader, shown))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  private boolean administersTarget(Intent.UnitIntent intent) {
    return intent.equals(new Intent.ViewHouseholdAdministration(target.getId()));
  }

  private boolean offeringSide(Intent.UnitIntent intent) {
    return intent.equals(new Intent.ViewProfileShareHouseholds(offered.getId()));
  }

  private ShareReference liveShare(UUID profileId, UUID householdId, Optional<UUID> offeredBy) {
    return stored(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .offeredByAccountId(offeredBy.orElse(null)));
  }

  /** The share of the offered Profile into the target at the status, read at {@code NOW}. */
  private ShareReference shareIn(ProfileShareStatus status) {
    return stored(offerOf(status));
  }

  /** The share, stored, as a page reads it at {@code NOW}. */
  private ShareReference stored(ProfileHouseholdShare.ProfileHouseholdShareBuilder<?, ?> share) {
    return ShareReference.of(shares.save(share.build()), NOW);
  }

  private ProfileHouseholdShare.ProfileHouseholdShareBuilder<?, ?> offerOf(
      ProfileShareStatus status) {
    return ProfileHouseholdShare.builder()
        .profileId(offered.getId())
        .householdId(target.getId())
        .status(status)
        .offeredByAccountId(offerer.getId())
        .expiresAt(NOW.plusSeconds(3600));
  }

  private void allowOnly(Predicate<Intent.UnitIntent> allowed) {
    authorization.decideUnitWith(
        intent ->
            allowed.test(intent)
                ? new Decision.Allowed<>(AuthorizationUnit.INSTANCE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));
  }

  private void failOnProfileSide() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.ViewProfileAdministration
                    || intent instanceof Intent.ViewProfileShareHouseholds
                ? new Decision.Failed<>(Decision.FailureCause.INVALID_SLICE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));
  }

  private AuthenticatedIdentity reader() {
    return authorization.currentIdentity();
  }
}
