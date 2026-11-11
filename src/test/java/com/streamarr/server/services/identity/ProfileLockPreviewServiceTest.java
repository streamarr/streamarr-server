package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import com.streamarr.server.domain.auth.UserAccount;
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
    authorization = new FakeAuthorizationService(reader);
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

    assertThat(preview)
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

    assertThat(preview)
        .singleElement()
        .satisfies(
            locks -> {
              assertThat(locks.profiles()).isEmpty();
              assertThat(locks.locksThisProfile()).isTrue();
            });
  }

  @Test
  @DisplayName("Should leave out a Household when the caller cannot see its Profile picker")
  void shouldLeaveOutAHouseholdWhenTheCallerCannotSeeItsProfilePicker() {
    var sam = available(ProfileFixture.defaultProfileBuilder().name("Sam").pinHash(A_PIN));
    available(ProfileFixture.defaultProfileBuilder().name("Mara"));
    var cabin = households.save(HouseholdFixture.defaultHouseholdBuilder().name("Cabin").build());
    shares.share(sam.getId(), cabin.getId(), false);
    var theo = profiles.save(ProfileFixture.defaultProfileBuilder().name("Theo").build());
    shares.share(theo.getId(), cabin.getId(), true);

    var preview =
        service
            .policyChangePreview(reader, sam.getId(), new ProfilePolicyTarget(ProfileKind.KID, 10))
            .orElseThrow();

    assertThat(preview)
        .extracting(locks -> locks.household().getName())
        .containsExactly("Smith Family");
  }

  @Test
  @DisplayName("Should list nothing when every Profile there already has a PIN")
  void shouldListNothingWhenEveryProfileThereAlreadyHasAPin() {
    var sam = available(ProfileFixture.defaultProfileBuilder().name("Sam").pinHash(A_PIN));
    available(ProfileFixture.defaultProfileBuilder().name("Mara").pinHash(A_PIN));

    assertThat(
            service.policyChangePreview(
                reader, sam.getId(), new ProfilePolicyTarget(ProfileKind.KID, 10)))
        .hasValue(List.of());
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

    assertThat(preview)
        .singleElement()
        .satisfies(
            locks -> {
              assertThat(locks.profiles()).extracting(Profile::getId).containsExactly(theo.getId());
              assertThat(locks.locksThisProfile()).isFalse();
            });
  }

  @Test
  @DisplayName("Should answer nothing when the caller may not read the Profile's administration")
  void shouldAnswerNothingWhenTheCallerMayNotReadTheProfilesAdministration() {
    var sam = available(ProfileFixture.defaultProfileBuilder().name("Sam"));
    available(ProfileFixture.defaultProfileBuilder().name("Mara"));
    authorization.denyAll();

    assertThat(
            service.policyChangePreview(
                reader, sam.getId(), new ProfilePolicyTarget(ProfileKind.KID, 10)))
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

  private Profile available(Profile.ProfileBuilder<?, ?> profile) {
    var saved = profiles.save(profile.householdId(smith.getId()).build());
    shares.share(saved.getId(), smith.getId(), true);
    return saved;
  }
}
