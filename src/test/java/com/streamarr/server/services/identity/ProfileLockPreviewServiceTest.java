package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
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
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfilePolicyTransition;
import com.streamarr.server.services.authorization.ProfilePolicyTransition.Classification;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Lock Preview Service Tests")
class ProfileLockPreviewServiceTest {

  private static final String A_PIN = "{noop}a-pin-hash";

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeHouseholdRepository households = new FakeHouseholdRepository(accounts);

  private Household smith;
  private UserAccount alex;
  private AuthenticatedIdentity reader;
  private FakeAuthorizationService authorization;
  private ProfileLockPreviewService service;

  @BeforeEach
  void setUp() {
    smith =
        households.save(HouseholdFixture.defaultHouseholdBuilder().name("Smith Family").build());
    var alexsProfile =
        available(ProfileFixture.defaultProfileBuilder().name("Alex").pinHash(A_PIN));
    alex =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(smith.getId())
                .personalProfileId(alexsProfile.getId())
                .build());
    reader =
        AuthenticatedIdentityFixture.accountScopedBuilder()
            .accountId(alex.getId())
            .householdId(smith.getId())
            .contextHouseholdId(smith.getId())
            .build();
    authorization =
        new FakeAuthorizationService(reader)
            .decidePolicyWith(change -> new Decision.Allowed<>(transitionOf(change)));
    service = new ProfileLockPreviewService(authorization, profiles, shares, accounts, households);
  }

  @Test
  @DisplayName("Should list the Adults without a PIN when an Adult becomes a Kid")
  void shouldListTheAdultsWithoutAPinWhenAnAdultBecomesAKid() {
    var sam = available(ProfileFixture.defaultProfileBuilder().name("Sam").pinHash(A_PIN));
    var mara = available(ProfileFixture.defaultProfileBuilder().name("Mara"));
    var theo = available(ProfileFixture.defaultProfileBuilder().name("Theo"));

    var preview =
        service
            .policyChangePreview(reader, sam.getId(), new ProfilePolicyTarget(ProfileKind.KID, 10))
            .orElseThrow();

    assertThat(preview.households())
        .singleElement()
        .satisfies(
            locks -> {
              assertThat(locks.household().getName()).isEqualTo("Smith Family");
              assertThat(locks.profiles())
                  .extracting(Profile::getId)
                  .containsExactly(mara.getId(), theo.getId());
              assertThat(locks.locksThisProfile()).isFalse();
            });
  }

  @Test
  @DisplayName("Should list the changed Kid when its limit is raised above another Kid's limit")
  void shouldListTheChangedKidWhenItsLimitIsRaisedAboveAnotherKidsLimit() {
    var sam = available(ProfileFixture.kidProfileBuilder().name("Sam").maximumAllowedRatingAge(7));
    available(ProfileFixture.kidProfileBuilder().name("Mara").maximumAllowedRatingAge(10));

    var preview =
        service
            .policyChangePreview(reader, sam.getId(), new ProfilePolicyTarget(ProfileKind.KID, 13))
            .orElseThrow();

    assertThat(preview.locksThisProfile()).isTrue();
    assertThat(preview.households())
        .singleElement()
        .satisfies(
            locks -> {
              assertThat(locks.profiles()).isEmpty();
              assertThat(locks.locksThisProfile()).isTrue();
            });
  }

  @Test
  @DisplayName(
      "Should list every Household where the Profile is available when a ServerAdmin previews the"
          + " change")
  void shouldListEveryHouseholdWhereTheProfileIsAvailableWhenAServerAdminPreviewsTheChange() {
    var sam = available(ProfileFixture.defaultProfileBuilder().name("Sam").pinHash(A_PIN));
    var mara = available(ProfileFixture.defaultProfileBuilder().name("Mara"));
    var cabin = households.save(HouseholdFixture.defaultHouseholdBuilder().name("Cabin").build());
    shares.share(sam.getId(), cabin.getId(), false);
    var theo = profiles.save(ProfileFixture.defaultProfileBuilder().name("Theo").build());
    shares.share(theo.getId(), cabin.getId(), true);

    var preview =
        service
            .policyChangePreview(reader, sam.getId(), new ProfilePolicyTarget(ProfileKind.KID, 10))
            .orElseThrow();

    assertThat(preview.households())
        .extracting(locks -> locks.household().getName(), locks -> ids(locks.profiles()))
        .containsExactly(
            tuple("Smith Family", List.of(mara.getId())), tuple("Cabin", List.of(theo.getId())));
    assertThat(preview.otherProfilesWillLock()).isFalse();
  }

  @Test
  @DisplayName("Should list nothing when every Profile there already has a PIN")
  void shouldListNothingWhenEveryProfileThereAlreadyHasAPin() {
    var sam = available(ProfileFixture.defaultProfileBuilder().name("Sam").pinHash(A_PIN));
    available(ProfileFixture.defaultProfileBuilder().name("Mara").pinHash(A_PIN));

    assertThat(
            service.policyChangePreview(
                reader, sam.getId(), new ProfilePolicyTarget(ProfileKind.KID, 10)))
        .hasValue(new ProfileLocks(false, List.of(), false));
  }

  @Test
  @DisplayName("Should list only what is not locked already when a Kid's limit changes")
  void shouldListOnlyWhatIsNotLockedAlreadyWhenAKidsLimitChanges() {
    var sam = available(ProfileFixture.kidProfileBuilder().name("Sam").maximumAllowedRatingAge(10));
    available(ProfileFixture.defaultProfileBuilder().name("Mara"));
    var theo =
        available(ProfileFixture.kidProfileBuilder().name("Theo").maximumAllowedRatingAge(7));

    var preview =
        service
            .policyChangePreview(reader, sam.getId(), new ProfilePolicyTarget(ProfileKind.KID, 5))
            .orElseThrow();

    assertThat(preview.households())
        .singleElement()
        .satisfies(
            locks -> {
              assertThat(locks.profiles()).extracting(Profile::getId).containsExactly(theo.getId());
              assertThat(locks.locksThisProfile()).isFalse();
            });
  }

  @Test
  @DisplayName("Should answer nothing when the caller may not make the change")
  void shouldAnswerNothingWhenTheCallerMayNotMakeTheChange() {
    var sam = available(ProfileFixture.defaultProfileBuilder().name("Sam"));
    available(ProfileFixture.defaultProfileBuilder().name("Mara"));
    authorization.decidePolicyWith(_ -> new Decision.Denied<>(Decision.DenialReason.POLICY));

    assertThat(
            service.policyChangePreview(
                reader, sam.getId(), new ProfilePolicyTarget(ProfileKind.KID, 10)))
        .isEmpty();
  }

  @Test
  @DisplayName("Should surface authorization unavailable when the preview cannot decide the change")
  void shouldSurfaceAuthorizationUnavailableWhenThePreviewCannotDecideTheChange() {
    var sam = available(ProfileFixture.defaultProfileBuilder().name("Sam"));
    available(ProfileFixture.defaultProfileBuilder().name("Mara"));
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var target = new ProfilePolicyTarget(ProfileKind.KID, 10);
    var samId = sam.getId();

    assertThatThrownBy(() -> service.policyChangePreview(reader, samId, target))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should preview the change when the caller may make it once it reauthenticates")
  void shouldPreviewTheChangeWhenTheCallerMayMakeItOnceItReauthenticates() {
    var sam = available(ProfileFixture.defaultProfileBuilder().name("Sam").pinHash(A_PIN));
    var mara = available(ProfileFixture.defaultProfileBuilder().name("Mara"));
    authorization.decidePolicyWith(
        _ -> new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED));

    var preview =
        service
            .policyChangePreview(reader, sam.getId(), new ProfilePolicyTarget(ProfileKind.KID, 10))
            .orElseThrow();

    assertThat(preview.households())
        .singleElement()
        .satisfies(
            locks ->
                assertThat(locks.profiles()).extracting(Profile::getId).contains(mara.getId()));
  }

  @Test
  @DisplayName("Should answer nothing when the ceiling is negative")
  void shouldAnswerNothingWhenTheCeilingIsNegative() {
    var sam = available(ProfileFixture.kidProfileBuilder().name("Sam"));

    assertThat(
            service.policyChangePreview(
                reader, sam.getId(), new ProfilePolicyTarget(ProfileKind.KID, -1)))
        .isEmpty();
    assertThat(
            service.creationPreview(
                reader, smith.getId(), new ProfilePolicyTarget(ProfileKind.KID, -1)))
        .isEmpty();
  }

  @Test
  @DisplayName("Should answer nothing when the Profile does not exist")
  void shouldAnswerNothingWhenTheProfileDoesNotExist() {
    assertThat(
            service.policyChangePreview(
                reader, UUID.randomUUID(), new ProfilePolicyTarget(ProfileKind.KID, 10)))
        .isEmpty();
  }

  @Test
  @DisplayName("Should list the Adults without a PIN when a Kid is added to the Household")
  void shouldListTheAdultsWithoutAPinWhenAKidIsAddedToTheHousehold() {
    var mara = available(ProfileFixture.defaultProfileBuilder().name("Mara"));
    var theo = available(ProfileFixture.defaultProfileBuilder().name("Theo"));

    var preview =
        service
            .creationPreview(reader, smith.getId(), new ProfilePolicyTarget(ProfileKind.KID, 10))
            .orElseThrow();

    assertThat(preview.households())
        .singleElement()
        .satisfies(
            locks -> {
              assertThat(locks.household().getName()).isEqualTo("Smith Family");
              assertThat(locks.profiles())
                  .extracting(Profile::getId)
                  .containsExactly(mara.getId(), theo.getId());
              assertThat(locks.locksThisProfile()).isFalse();
            });
  }

  @Test
  @DisplayName("Should say the new Profile would lock when an Adult is added where a Kid lives")
  void shouldSayTheNewProfileWouldLockWhenAnAdultIsAddedWhereAKidLives() {
    available(ProfileFixture.kidProfileBuilder().name("Sam").maximumAllowedRatingAge(10));

    var preview =
        service
            .creationPreview(
                reader, smith.getId(), new ProfilePolicyTarget(ProfileKind.ADULT, null))
            .orElseThrow();

    assertThat(preview.locksThisProfile()).isTrue();
    assertThat(preview.households())
        .singleElement()
        .satisfies(
            locks -> {
              assertThat(locks.profiles()).isEmpty();
              assertThat(locks.locksThisProfile()).isTrue();
            });
  }

  @Test
  @DisplayName(
      "Should list the Household when a ServerAdmin previews adding a Kid where it cannot use the"
          + " Household")
  void shouldListTheHouseholdWhenAServerAdminPreviewsAddingAKidWhereItCannotUseTheHousehold() {
    var cabin = households.save(HouseholdFixture.defaultHouseholdBuilder().name("Cabin").build());
    var theo = profiles.save(ProfileFixture.defaultProfileBuilder().name("Theo").build());
    shares.share(theo.getId(), cabin.getId(), true);

    var preview =
        service
            .creationPreview(reader, cabin.getId(), new ProfilePolicyTarget(ProfileKind.KID, 10))
            .orElseThrow();

    assertThat(preview.households())
        .extracting(locks -> locks.household().getName(), locks -> ids(locks.profiles()))
        .containsExactly(tuple("Cabin", List.of(theo.getId())));
  }

  @Test
  @DisplayName("Should answer nothing when the caller may not create a Profile in the Household")
  void shouldAnswerNothingWhenTheCallerMayNotCreateAProfileInTheHousehold() {
    available(ProfileFixture.defaultProfileBuilder().name("Mara"));
    authorization.denyAll();

    assertThat(
            service.creationPreview(
                reader, smith.getId(), new ProfilePolicyTarget(ProfileKind.KID, 10)))
        .isEmpty();
  }

  @Test
  @DisplayName("Should answer nothing when the Household does not exist")
  void shouldAnswerNothingWhenTheHouseholdDoesNotExist() {
    assertThat(
            service.creationPreview(
                reader, UUID.randomUUID(), new ProfilePolicyTarget(ProfileKind.KID, 10)))
        .isEmpty();
  }

  private static ProfilePolicyTransition transitionOf(Intent.ProfilePolicyChange change) {
    var target = ((Intent.SetProfilePolicy) change).target();
    return new ProfilePolicyTransition(
        target.kind(), target.maximumAllowedRatingAge(), Classification.ORDINARY_EDIT);
  }

  private static List<UUID> ids(List<Profile> profiles) {
    return profiles.stream().map(Profile::getId).toList();
  }

  private Profile available(Profile.ProfileBuilder<?, ?> profile) {
    var saved = profiles.save(profile.householdId(smith.getId()).build());
    shares.share(saved.getId(), smith.getId(), true);
    return saved;
  }
}
