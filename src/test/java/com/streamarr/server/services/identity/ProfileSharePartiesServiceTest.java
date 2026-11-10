package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Share Parties Service Tests")
class ProfileSharePartiesServiceTest {

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
    share = new ShareReference(offered.getId(), target.getId(), Optional.of(offerer.getId()));
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
  @DisplayName("Should show the Profile when the reader may see its administration")
  void shouldShowProfileWhenReaderMaySeeItsAdministration() {
    allowOnly(intent -> intent.equals(new Intent.ViewProfileAdministration(offered.getId())));

    assertThat(service.profiles(reader(), Set.of(share))).containsKey(share);
  }

  @Test
  @DisplayName("Should hide the Profile when the reader has neither authority")
  void shouldHideProfileWhenReaderHasNeitherAuthority() {
    allowOnly(intent -> intent.equals(new Intent.ViewProfileShareHouseholds(offered.getId())));

    assertThat(service.profiles(reader(), Set.of(share))).isEmpty();
  }

  @Test
  @DisplayName("Should mark a Personal Profile when an Account is represented by it")
  void shouldMarkPersonalProfileWhenAccountIsRepresentedByIt() {
    allowOnly(this::administersTarget);
    var person = profiles.save(ProfileFixture.kidProfileBuilder().name("Jo").build());
    accounts.save(AccountFixture.defaultAccountBuilder().personalProfileId(person.getId()).build());
    var personShare = new ShareReference(person.getId(), target.getId(), Optional.empty());

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
    var ownShare = new ShareReference(own.getId(), target.getId(), Optional.empty());

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
    var gone = new ShareReference(UUID.randomUUID(), target.getId(), Optional.of(offerer.getId()));

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
    var structural = new ShareReference(offered.getId(), target.getId(), Optional.empty());
    var orphaned =
        new ShareReference(offered.getId(), target.getId(), Optional.of(UUID.randomUUID()));

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
  @DisplayName("Should show the Household when the reader administers it")
  void shouldShowHouseholdWhenReaderAdministersIt() {
    allowOnly(this::administersTarget);

    assertThat(service.households(reader(), Set.of(share))).containsKey(share);
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
    var gone = new ShareReference(UUID.randomUUID(), UUID.randomUUID(), Optional.empty());

    assertThat(service.households(reader(), Set.of(gone))).isEmpty();
  }

  // ---- Many shares, one reader.

  @Test
  @DisplayName("Should decide each share by its own Household and Profile when a page is mixed")
  void shouldDecideEachShareByItsOwnHouseholdAndProfileWhenPageIsMixed() {
    var elsewhere = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var other = profiles.save(ProfileFixture.defaultProfileBuilder().build());
    var intoElsewhere =
        new ShareReference(offered.getId(), elsewhere.getId(), Optional.of(offerer.getId()));
    var otherIntoTarget =
        new ShareReference(other.getId(), target.getId(), Optional.of(offerer.getId()));
    allowOnly(this::administersTarget);

    var names = service.offererNames(reader(), Set.of(share, intoElsewhere, otherIntoTarget));
    var shown = service.households(reader(), Set.of(share, intoElsewhere, otherIntoTarget));

    assertThat(names).containsOnlyKeys(share, otherIntoTarget);
    assertThat(shown).containsOnlyKeys(share, otherIntoTarget);
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
