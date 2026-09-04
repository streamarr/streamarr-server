package com.streamarr.server.services.authorization.cedar;

import static com.streamarr.server.fixtures.ProfileHouseholdShareFixture.activeShareBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeDeviceRegistrationRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfilePolicyTransition;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The viewing and read policies against the real engine and the real contributors over fakes: every
 * permit has its allow case, its deny case, and its missing-relationship case.
 */
@Tag("UnitTest")
@DisplayName("Cedar Identity Policies Tests")
class CedarIdentityPoliciesTest {

  private static final Decision<AuthorizationUnit> ALLOWED =
      new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
  private static final Decision<AuthorizationUnit> DENIED =
      new Decision.Denied<>(Decision.DenialReason.POLICY);
  private static final Decision<AuthorizationUnit> REAUTHENTICATION_REQUIRED =
      new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED);

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeProfileManagerRepository managers = new FakeProfileManagerRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeProfileManagerInvitationRepository managerInvitations =
      new FakeProfileManagerInvitationRepository();
  private final FakeDeviceRegistrationRepository registrations =
      new FakeDeviceRegistrationRepository();

  private final AuthorizationService authorizationService =
      new SecurityContextAuthorizationService(
          new CedarAuthorizationDecider(
              new BasicAuthorizationEngine(),
              new CedarPolicyBundle(
                  new BasicAuthorizationEngine(), new PathMatchingResourcePatternResolver()),
              new SliceAssembler(
                  List.of(
                      new LivePrincipalAuthorityContributor(accounts),
                      new SignedPrincipalContextContributor(),
                      new ContextHouseholdAccessContributor(accounts),
                      new SessionLivenessContributor(sessions),
                      new ProfileAvailabilityContributor(profiles),
                      new ProfileManagementContributor(profiles, managers, shares, accounts),
                      new AccountHouseholdContributor(accounts),
                      new LivePrincipalHouseholdContributor(accounts),
                      new PrincipalEligibilityContributor(accounts, profiles),
                      new ProfileSupervisionContributor(accounts, profiles, shares),
                      new ProfileDeletionContributor(accounts, managers, shares, Clock.systemUTC()),
                      new ShareFactsContributor(shares, profiles, managers, accounts),
                      new ManagerInvitationFactsContributor(managerInvitations),
                      new RegistrationFactsContributor(registrations, accounts))),
              new IntentPlanner(new ProfilePolicyPlanner(profiles)),
              ContributorStubs.systemClockFreshness(),
              new SimpleMeterRegistry()),
          accounts);

  private UserAccount account;
  private Profile personal;
  private AuthSession session;
  private UUID visitedHouseholdId;

  @BeforeEach
  void setUp() {
    account = accounts.save(AccountFixture.defaultAccountBuilder().build());
    personal =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .id(account.getPersonalProfileId())
                .householdId(account.getHouseholdId())
                .build());
    shares.save(
        activeShareBuilder()
            .profileId(personal.getId())
            .householdId(account.getHouseholdId())
            .structural(true)
            .build());
    session =
        sessions.save(
            AuthSession.builder()
                .accountId(account.getId())
                .contextHouseholdId(account.getHouseholdId())
                .selectedProfileId(personal.getId())
                .build());
    visitedHouseholdId = UUID.randomUUID();
  }

  @Nested
  @DisplayName("Profile picker and selection")
  class PickerAndSelection {

    @Test
    @DisplayName(
        "Should allow the membership picker and deny elsewhere when the context Household varies")
    void shouldAllowMembershipPickerAndDenyElsewhereWhenContextHouseholdVaries() {
      assertThat(decide(atHome(), new Intent.ViewProfilePicker())).isEqualTo(ALLOWED);
      assertThat(decide(visiting(), new Intent.ViewProfilePicker())).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should allow the picker when the visited Household share is active")
    void shouldAllowPickerWhenVisitedHouseholdShareIsActive() {
      shares.share(personal.getId(), visitedHouseholdId, false);

      assertThat(decide(visiting(), new Intent.ViewProfilePicker())).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow selection without a PIN when the Profile is available and unlocked")
    void shouldAllowSelectionWithoutPinWhenProfileIsAvailableAndUnlocked() {
      assertThat(decide(atHome(), new Intent.SelectProfile(personal.getId(), false)))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny selection when the Profile is unavailable in the context")
    void shouldDenySelectionWhenProfileIsUnavailableInContext() {
      var elsewhere = profiles.save(ProfileFixture.defaultProfileBuilder().build());

      assertThat(decide(atHome(), new Intent.SelectProfile(elsewhere.getId(), true)))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName(
        "Should lock an unpinned Adult when a Kid is available while keeping the Kid selectable")
    void shouldDenySelectionWhenSafetyRuleLocksProfile() {
      var kid =
          profiles.save(
              ProfileFixture.kidProfileBuilder().householdId(account.getHouseholdId()).build());
      shares.share(kid.getId(), account.getHouseholdId(), false);

      assertThat(decide(atHome(), new Intent.SelectProfile(personal.getId(), false)))
          .isEqualTo(DENIED);
      // The Kid causes the unpinned Adult lock; the Kid itself remains safe to select.
      assertThat(decide(atHome(), new Intent.SelectProfile(kid.getId(), false))).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should require the verified PIN when the Profile has one")
    void shouldRequireVerifiedPinWhenProfileHasOne() {
      personal.setPinHash("{argon2id}x");
      profiles.save(personal);

      assertThat(decide(atHome(), new Intent.SelectProfile(personal.getId(), false)))
          .isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.SelectProfile(personal.getId(), true)))
          .isEqualTo(ALLOWED);
    }
  }

  @Nested
  @DisplayName("Playback")
  class Playback {

    @Test
    @DisplayName("Should allow playback when every live fact holds")
    void shouldAllowPlaybackWhenEveryLiveFactHolds() {
      assertThat(decide(watching(personal.getId()), new Intent.Playback())).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny playback when the session is revoked")
    void shouldDenyPlaybackWhenSessionIsRevoked() {
      sessions.revoke(session.getId(), SessionRevocationReason.LOGOUT, Instant.now());

      assertThat(decide(watching(personal.getId()), new Intent.Playback())).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should deny playback when the live session no longer selects the Profile")
    void shouldDenyPlaybackWhenLiveSessionNoLongerSelectsProfile() {
      var identity = watching(personal.getId());
      session.setSelectedProfileId(null);
      sessions.save(session);

      assertThat(decide(identity, new Intent.Playback())).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should deny playback when the Account is disabled")
    void shouldDenyPlaybackWhenAccountIsDisabled() {
      account.setEnabled(false);
      accounts.save(account);

      assertThat(decide(watching(personal.getId()), new Intent.Playback())).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should deny playback when the selected Profile is no longer shared")
    void shouldDenyPlaybackWhenSelectedProfileIsNoLongerShared() {
      var managed =
          profiles.save(
              ProfileFixture.defaultProfileBuilder().householdId(account.getHouseholdId()).build());
      session.setSelectedProfileId(managed.getId());
      sessions.save(session);
      var identity = watching(managed.getId());
      var share = shares.share(managed.getId(), account.getHouseholdId(), false);

      assertThat(decide(identity, new Intent.Playback())).isEqualTo(ALLOWED);

      share.setStatus(ProfileShareStatus.ENDED);
      share.setEndedAt(Instant.now());
      shares.save(share);

      assertThat(decide(identity, new Intent.Playback())).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should deny playback when the Account may no longer use the context Household")
    void shouldDenyPlaybackWhenAccountMayNoLongerUseContextHousehold() {
      var identity =
          AuthenticatedIdentity.builder()
              .accountId(account.getId())
              .authSessionId(session.getId())
              .scope(TokenScope.PLAYBACK)
              .householdId(account.getHouseholdId())
              .householdRole(HouseholdRole.ADMIN)
              .contextHouseholdId(visitedHouseholdId)
              .profileId(personal.getId())
              .streamSessionId(UUID.randomUUID())
              .build();

      assertThat(decide(identity, new Intent.Playback())).isEqualTo(DENIED);
    }
  }

  @Nested
  @DisplayName("Reads")
  class Reads {

    @Test
    @DisplayName(
        "Should allow activity when the caller is the selected Profile, a manager, or a live ServerAdmin")
    void shouldAllowActivityWhenCallerIsSelectedProfileManagerOrLiveServerAdmin() {
      var other = profiles.save(ProfileFixture.defaultProfileBuilder().build());

      assertThat(
              decide(watching(personal.getId()), new Intent.ViewProfileActivity(personal.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(watching(personal.getId()), new Intent.ViewProfileActivity(other.getId())))
          .isEqualTo(DENIED);
      // Self-management of the unrestricted Adult Personal Profile counts as management.
      assertThat(decide(atHome(), new Intent.ViewProfileActivity(personal.getId())))
          .isEqualTo(ALLOWED);

      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(other.getId()).build());
      assertThat(decide(atHome(), new Intent.ViewProfileActivity(other.getId())))
          .isEqualTo(ALLOWED);

      managers.deleteAll();
      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.ViewProfileActivity(other.getId())))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should fail closed when the activity Profile is unknown")
    void shouldFailClosedWhenActivityProfileIsUnknown() {
      assertThat(decide(atHome(), new Intent.ViewProfileActivity(UUID.randomUUID())))
          .isEqualTo(new Decision.Failed<>(Decision.FailureCause.INVALID_SLICE));
    }

    @Test
    @DisplayName("Should fail closed when the selected Profile was deleted")
    void shouldFailClosedWhenSelectedProfileWasDeleted() {
      var staleIdentity =
          identityBuilder().scope(TokenScope.PROFILE).profileId(personal.getId()).build();
      profiles.deleteById(personal.getId());

      assertThat(decide(staleIdentity, new Intent.ViewProfileActivity(personal.getId())))
          .isEqualTo(new Decision.Failed<>(Decision.FailureCause.INVALID_SLICE));
    }

    @Test
    @DisplayName(
        "Should allow Household administration when the caller is its HouseholdAdmin or ServerAdmin")
    void shouldAllowHouseholdAdministrationWhenCallerIsHouseholdAdminOrServerAdmin() {
      assertThat(decide(atHome(), new Intent.ViewHouseholdAdministration(account.getHouseholdId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.ViewHouseholdAdministration(visitedHouseholdId)))
          .isEqualTo(DENIED);
      assertThat(decide(member(), new Intent.ViewHouseholdAdministration(account.getHouseholdId())))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(member(), new Intent.ViewHouseholdAdministration(visitedHouseholdId)))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName(
        "Should allow Account administration when the caller is a HouseholdAdmin or ServerAdmin")
    void shouldAllowAccountAdministrationWhenCallerIsHouseholdAdminOrServerAdmin() {
      var neighbour =
          accounts.save(
              AccountFixture.defaultAccountBuilder().householdId(account.getHouseholdId()).build());
      var stranger = accounts.save(AccountFixture.defaultAccountBuilder().build());

      assertThat(decide(atHome(), new Intent.ViewAccountAdministration(neighbour.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.ViewAccountAdministration(stranger.getId())))
          .isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.ViewAccountAdministration(UUID.randomUUID())))
          .isEqualTo(DENIED);
      assertThat(decide(member(), new Intent.ViewAccountAdministration(neighbour.getId())))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(member(), new Intent.ViewAccountAdministration(stranger.getId())))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow own Account administration when the caller is a HouseholdAdmin")
    void shouldAllowOwnAccountAdministrationWhenCallerIsHouseholdAdmin() {
      assertThat(decide(atHome(), new Intent.ViewAccountAdministration(account.getId())))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName(
        "Should deny own Account administration when signed and live Household facts conflict")
    void shouldDenyOwnAccountAdministrationWhenSignedAndLiveHouseholdFactsConflict() {
      var staleIdentity = atHome();
      account.setHouseholdId(visitedHouseholdId);
      accounts.save(account);

      assertThat(decide(staleIdentity, new Intent.ViewAccountAdministration(account.getId())))
          .isEqualTo(new Decision.Failed<>(Decision.FailureCause.INVALID_SLICE));
    }

    @Test
    @DisplayName(
        "Should allow Profile administration when the caller is a manager, hosting admin, or ServerAdmin")
    void shouldAllowProfileAdministrationWhenCallerIsManagerHostingAdminOrServerAdmin() {
      var visitor = profiles.save(ProfileFixture.defaultProfileBuilder().build());

      assertThat(decide(atHome(), new Intent.ViewProfileAdministration(visitor.getId())))
          .isEqualTo(DENIED);
      shares.share(visitor.getId(), account.getHouseholdId(), false);
      assertThat(decide(atHome(), new Intent.ViewProfileAdministration(visitor.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(member(), new Intent.ViewProfileAdministration(visitor.getId())))
          .isEqualTo(DENIED);

      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(visitor.getId()).build());
      assertThat(decide(member(), new Intent.ViewProfileAdministration(visitor.getId())))
          .isEqualTo(ALLOWED);
      managers.deleteAll();

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(member(), new Intent.ViewProfileAdministration(visitor.getId())))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName(
        "Should not treat a restricted Personal Profile as self-managed when evaluating administration")
    void shouldNotTreatRestrictedPersonalProfileAsSelfManagedWhenEvaluatingAdministration() {
      personal.setMaximumAllowedRatingAge(12);
      profiles.save(personal);

      assertThat(decide(member(), new Intent.ViewProfileAdministration(personal.getId())))
          .isEqualTo(DENIED);
    }
  }

  @Nested
  @DisplayName("Administration")
  class Administration {

    @Test
    @DisplayName(
        "Should allow ServerAdmin authority changes when the caller is fresh, live, and enabled")
    void shouldAllowServerAdminAuthorityChangesWhenCallerFreshLiveEnabledAdmin() {
      account.setServerAdmin(true);
      accounts.save(account);
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());
      var fresh = withReauthenticatedAt(atHome(), Instant.now());

      assertThat(decide(fresh, new Intent.GrantServerAdmin(target.getId()))).isEqualTo(ALLOWED);
      assertThat(decide(fresh, new Intent.RevokeServerAdmin(target.getId()))).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should require reauthentication when only the ceremony is missing")
    void shouldRequireReauthenticationWhenOnlyCeremonyIsMissing() {
      account.setServerAdmin(true);
      accounts.save(account);
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());

      assertThat(decide(atHome(), new Intent.GrantServerAdmin(target.getId())))
          .isEqualTo(REAUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("Should report not fresh when the ceremony claim is stale or future-dated")
    void shouldReportNotFreshWhenCeremonyClaimStaleOrFutureDated() {
      account.setServerAdmin(true);
      accounts.save(account);
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());
      var stale = withReauthenticatedAt(atHome(), Instant.now().minus(Duration.ofHours(1)));
      var future = withReauthenticatedAt(atHome(), Instant.now().plus(Duration.ofHours(1)));

      assertThat(decide(stale, new Intent.GrantServerAdmin(target.getId())))
          .isEqualTo(REAUTHENTICATION_REQUIRED);
      assertThat(decide(future, new Intent.GrantServerAdmin(target.getId())))
          .isEqualTo(REAUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("Should keep a policy denial when authority is missing")
    void shouldKeepPolicyDenialWhenAuthorityMissing() {
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());
      var fresh = withReauthenticatedAt(atHome(), Instant.now());

      assertThat(decide(atHome(), new Intent.GrantServerAdmin(target.getId()))).isEqualTo(DENIED);
      assertThat(decide(fresh, new Intent.GrantServerAdmin(target.getId()))).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should keep the policy denial when the admin Account is disabled")
    void shouldKeepPolicyDenialWhenAdminAccountIsDisabled() {
      account.setServerAdmin(true);
      account.setEnabled(false);
      accounts.save(account);
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());

      assertThat(decide(atHome(), new Intent.GrantServerAdmin(target.getId()))).isEqualTo(DENIED);
    }

    @Test
    @DisplayName(
        "Should allow Account administration writes only when the caller is live ServerAdmin")
    void shouldAllowAccountAdministrationWritesOnlyWhenCallerLiveServerAdmin() {
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());

      assertThat(decide(atHome(), new Intent.GrantHouseholdAdmin(target.getId())))
          .isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.RevokeHouseholdAdmin(target.getId())))
          .isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.DisableAccount(target.getId()))).isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.EnableAccount(target.getId()))).isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.GrantHouseholdAdmin(target.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.RevokeHouseholdAdmin(target.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.DisableAccount(target.getId()))).isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.EnableAccount(target.getId()))).isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.CreateHousehold())).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny Household creation when the caller is not live ServerAdmin")
    void shouldDenyHouseholdCreationWhenCallerNotLiveServerAdmin() {
      assertThat(decide(atHome(), new Intent.CreateHousehold())).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should allow the Household catalogue only when the caller is live ServerAdmin")
    void shouldAllowHouseholdCatalogueOnlyWhenCallerLiveServerAdmin() {
      assertThat(decide(atHome(), new Intent.ViewHouseholds())).isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.ViewHouseholds())).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName(
        "Should allow Household rename when the caller is its live HouseholdAdmin or ServerAdmin")
    void shouldAllowHouseholdRenameWhenCallerLiveHouseholdAdminOrServerAdmin() {
      var home = account.getHouseholdId();

      assertThat(decide(atHome(), new Intent.RenameHousehold(home))).isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.RenameHousehold(visitedHouseholdId)))
          .isEqualTo(DENIED);

      account.setHouseholdRole(HouseholdRole.MEMBER);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.RenameHousehold(home))).isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.RenameHousehold(visitedHouseholdId)))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow Account rename when the caller is itself or ServerAdmin")
    void shouldAllowAccountRenameWhenCallerSelfOrServerAdmin() {
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());

      assertThat(decide(member(), new Intent.RenameAccount(account.getId()))).isEqualTo(ALLOWED);
      assertThat(decide(member(), new Intent.RenameAccount(target.getId()))).isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(member(), new Intent.RenameAccount(target.getId()))).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny self rename when the Account is disabled")
    void shouldDenySelfRenameWhenAccountDisabled() {
      account.setEnabled(false);
      accounts.save(account);

      assertThat(decide(member(), new Intent.RenameAccount(account.getId()))).isEqualTo(DENIED);
    }
  }

  @Nested
  @DisplayName("Profile management")
  class ProfileManagement {

    @Test
    @DisplayName(
        "Should allow Profile creation when the caller is an eligible local admin or ServerAdmin")
    void shouldAllowProfileCreationWhenCallerIsEligibleLocalAdminOrServerAdmin() {
      assertThat(decide(atHome(), new Intent.CreateProfile(account.getHouseholdId())))
          .isEqualTo(ALLOWED);
      assertThat(
              decide(atHome(), new Intent.CreateProfileWithLocalManager(account.getHouseholdId())))
          .isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.CreateProfile(visitedHouseholdId))).isEqualTo(DENIED);

      // The live row decides the role, not the token claim.
      account.setHouseholdRole(HouseholdRole.MEMBER);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.CreateProfile(account.getHouseholdId())))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.CreateProfile(visitedHouseholdId))).isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.CreateProfileWithLocalManager(visitedHouseholdId)))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should refuse Profile creation when the admin's own Profile is restricted")
    void shouldRefuseProfileCreationWhenAdminOwnProfileIsRestricted() {
      personal.setMaximumAllowedRatingAge(12);
      profiles.save(personal);

      assertThat(decide(atHome(), new Intent.CreateProfile(account.getHouseholdId())))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName(
        "Should restrict Profile edits when the caller is a manager or share-derived supervisor")
    void shouldRestrictProfileEditsWhenCallerIsManagerOrSupervisor() {
      var kid = profiles.save(ProfileFixture.kidProfileBuilder().build());
      shares.save(
          activeShareBuilder()
              .profileId(kid.getId())
              .householdId(account.getHouseholdId())
              .build());

      // Supervising HouseholdAdmin (restricted Profile shared into their Household).
      assertThat(decide(atHome(), new Intent.RenameProfile(kid.getId()))).isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.ManageProfilePin(kid.getId()))).isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.ChangeProfileKind(kid.getId(), ProfileKind.ADULT)))
          .isEqualTo(DENIED);

      // A live MEMBER of the hosting Household supervises nothing.
      account.setHouseholdRole(HouseholdRole.MEMBER);
      accounts.save(account);
      assertThat(decide(member(), new Intent.RenameProfile(kid.getId()))).isEqualTo(DENIED);

      // A direct manager changes kind (ceilinged: the target stays restricted).
      kid.setMaximumAllowedRatingAge(12);
      profiles.save(kid);
      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(kid.getId()).build());
      assertThat(decide(member(), new Intent.ChangeProfileKind(kid.getId(), ProfileKind.ADULT)))
          .isEqualTo(
              new Decision.Allowed<>(
                  new ProfilePolicyTransition(
                      ProfileKind.ADULT, 12, ProfilePolicyTransition.Classification.KIND_CHANGE)));
    }

    @Test
    @DisplayName("Should deny ordinary PIN management when ServerAdmin has no Profile relationship")
    void shouldDenyOrdinaryPinManagementWhenServerAdminHasNoProfileRelationship() {
      var unrelated = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      account.setServerAdmin(true);
      accounts.save(account);

      assertThat(decide(atHome(), new Intent.ManageProfilePin(unrelated.getId())))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should require fresh reauthentication when lifting the final restriction")
    void shouldRequireFreshReauthenticationWhenLiftingFinalRestriction() {
      var kid = profiles.save(ProfileFixture.kidProfileBuilder().build());
      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(kid.getId()).build());
      var lift = new Intent.ChangeProfileKind(kid.getId(), ProfileKind.ADULT);

      assertThat(decide(atHome(), lift))
          .isEqualTo(new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED));
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), lift))
          .isEqualTo(
              new Decision.Allowed<>(
                  new ProfilePolicyTransition(
                      ProfileKind.ADULT,
                      null,
                      ProfilePolicyTransition.Classification.LIFT_FINAL_RESTRICTION)));
    }

    @Test
    @DisplayName(
        "Should allow restricting a sovereign Adult when the caller is a fresh ServerAdmin")
    void shouldAllowRestrictingSovereignAdultWhenCallerIsFreshServerAdmin() {
      var sovereign = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      profiles.linkTo(sovereign.getId(), UUID.randomUUID());
      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(sovereign.getId()).build());
      var restrict = new Intent.SetProfileContentCeiling(sovereign.getId(), 12);

      // Even a fresh direct manager cannot restrict a sovereign Adult.
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), restrict))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), restrict))
          .isEqualTo(new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED));
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), restrict))
          .isEqualTo(
              new Decision.Allowed<>(
                  new ProfilePolicyTransition(
                      ProfileKind.ADULT,
                      12,
                      ProfilePolicyTransition.Classification.RESTRICT_SOVEREIGN_ADULT)));
    }

    @Test
    @DisplayName("Should return the normalized transition when the policy change is allowed")
    void shouldReturnNormalizedTransitionWhenPolicyChangeIsAllowed() {
      var kid = profiles.save(ProfileFixture.kidProfileBuilder().build());
      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(kid.getId()).build());

      var decision = decide(atHome(), new Intent.SetProfileContentCeiling(kid.getId(), 12));

      assertThat(decision)
          .isEqualTo(
              new Decision.Allowed<>(
                  new ProfilePolicyTransition(
                      ProfileKind.KID, 12, ProfilePolicyTransition.Classification.ORDINARY_EDIT)));
    }

    @Test
    @DisplayName("Should allow the administrative PIN reset when the caller is a fresh ServerAdmin")
    void shouldAllowAdministrativePinResetWhenCallerIsFreshServerAdmin() {
      var reset = new Intent.AdministrativelyResetProfilePin(personal.getId());

      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), reset)).isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), reset)).isEqualTo(REAUTHENTICATION_REQUIRED);
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), reset)).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow ordinary deletion when the caller is the fresh sole manager")
    void shouldAllowOrdinaryDeletionWhenCallerIsFreshSoleManager() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(orphan.getId()).build());
      var delete = new Intent.DeleteProfile(orphan.getId());
      var fresh = withReauthenticatedAt(atHome(), Instant.now());

      assertThat(decide(atHome(), delete)).isEqualTo(REAUTHENTICATION_REQUIRED);
      assertThat(decide(fresh, delete)).isEqualTo(ALLOWED);

      // A live share keeps the Profile undeletable.
      var share = shares.share(orphan.getId(), visitedHouseholdId, false);
      assertThat(decide(fresh, delete)).isEqualTo(DENIED);
      shares.deleteById(share.getId());

      // A co-manager keeps a veto until they relinquish.
      managers.save(
          ProfileManager.builder().accountId(UUID.randomUUID()).profileId(orphan.getId()).build());
      assertThat(decide(fresh, delete)).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should refuse supervision when the admin's own Profile is restricted")
    void shouldRefuseSupervisionWhenAdminOwnProfileIsRestricted() {
      var kid = profiles.save(ProfileFixture.kidProfileBuilder().build());
      shares.share(kid.getId(), account.getHouseholdId(), false);
      personal.setMaximumAllowedRatingAge(12);
      profiles.save(personal);

      assertThat(decide(atHome(), new Intent.RenameProfile(kid.getId()))).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should fail closed when a policy change targets a Profile that does not exist")
    void shouldFailClosedWhenPolicyChangeTargetsMissingProfile() {
      assertThat(
              decide(
                  withReauthenticatedAt(atHome(), Instant.now()),
                  new Intent.ChangeProfileKind(UUID.randomUUID(), ProfileKind.ADULT)))
          .isEqualTo(new Decision.Failed<>(Decision.FailureCause.INVALID_SLICE));
    }

    @Test
    @DisplayName("Should refuse deletion when a linked Personal Profile is targeted standalone")
    void shouldRefuseDeletionWhenLinkedPersonalProfileIsTargetedStandalone() {
      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(personal.getId()).build());
      // The personal Profile is linked (some Account's personalProfileId points at it).
      var owner = accounts.save(AccountFixture.defaultAccountBuilder().build());
      owner.setPersonalProfileId(personal.getId());
      accounts.save(owner);

      assertThat(
              decide(
                  withReauthenticatedAt(atHome(), Instant.now()),
                  new Intent.DeleteProfile(personal.getId())))
          .isEqualTo(DENIED);
    }
  }

  @Nested
  @DisplayName("Sharing")
  class Sharing {

    @Test
    @DisplayName(
        "Should authorize a share offer when the caller has manager or sovereign authority")
    void shouldAuthorizeShareOfferWhenCallerHasManagerOrSovereignAuthority() {
      // The principal's own unrestricted Adult Personal Profile: offerable by itself.
      assertThat(decide(atHome(), new Intent.OfferProfileShare(personal.getId())))
          .isEqualTo(ALLOWED);

      // A retained direct manager of someone ELSE's sovereign Personal Profile cannot offer it.
      var other = accounts.save(AccountFixture.defaultAccountBuilder().build());
      var otherPersonal =
          profiles.save(
              ProfileFixture.defaultProfileBuilder().id(other.getPersonalProfileId()).build());
      managers.save(
          ProfileManager.builder()
              .accountId(account.getId())
              .profileId(otherPersonal.getId())
              .build());
      assertThat(decide(atHome(), new Intent.OfferProfileShare(otherPersonal.getId())))
          .isEqualTo(DENIED);

      // An unlinked Profile is offered by any direct manager — and, apart from ServerAdmin, nobody
      // else.
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      assertThat(decide(atHome(), new Intent.OfferProfileShare(orphan.getId()))).isEqualTo(DENIED);
      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(orphan.getId()).build());
      assertThat(decide(atHome(), new Intent.OfferProfileShare(orphan.getId()))).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny sovereign share ending when the Personal Profile is restricted")
    void shouldDenySovereignShareEndingWhenPersonalProfileIsRestricted() {
      var visit =
          shares.save(
              activeShareBuilder()
                  .profileId(personal.getId())
                  .householdId(visitedHouseholdId)
                  .build());
      personal.setMaximumAllowedRatingAge(12);
      profiles.save(personal);
      account.setHouseholdRole(HouseholdRole.MEMBER);
      accounts.save(account);

      // A supervised person no longer ends their own Profile's visits.
      assertThat(decide(member(), new Intent.EndProfileShare(visit.getId()))).isEqualTo(DENIED);
    }

    @Test
    @DisplayName(
        "Should allow a pending offer decision when the caller is target admin or ServerAdmin")
    void shouldAllowPendingOfferDecisionWhenCallerIsTargetAdminOrServerAdmin() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var offer =
          shares.save(
              pendingShareBuilder()
                  .profileId(orphan.getId())
                  .householdId(account.getHouseholdId())
                  .build());

      assertThat(decide(atHome(), new Intent.AcceptProfileShare(offer.getId()))).isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.RejectProfileShare(offer.getId()))).isEqualTo(ALLOWED);

      // A live MEMBER of the target cannot; the token's ADMIN claim never decides.
      account.setHouseholdRole(HouseholdRole.MEMBER);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.AcceptProfileShare(offer.getId()))).isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.AcceptProfileShare(offer.getId()))).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should refuse deciding a share when it does not exist")
    void shouldRefuseDecidingShareWhenItDoesNotExist() {
      assertThat(decide(atHome(), new Intent.AcceptProfileShare(UUID.randomUUID())))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName(
        "Should allow the sovereign Account to cancel a pending offer of its own Personal Profile")
    void shouldAllowSovereignAccountToCancelPendingOfferOfOwnPersonalProfile() {
      var formerManager = accounts.save(AccountFixture.defaultAccountBuilder().build());
      var offer =
          shares.save(
              pendingShareBuilder()
                  .profileId(personal.getId())
                  .householdId(visitedHouseholdId)
                  .build());
      offer.setOfferedByAccountId(formerManager.getId());
      shares.save(offer);

      assertThat(decide(atHome(), new Intent.CancelProfileShare(offer.getId()))).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow the offerer and deny a stranger when a pending offer is canceled")
    void shouldAllowOffererAndDenyStrangerWhenPendingOfferIsCanceled() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var offer =
          shares.save(
              pendingShareBuilder()
                  .profileId(orphan.getId())
                  .householdId(visitedHouseholdId)
                  .build());
      offer.setOfferedByAccountId(account.getId());
      shares.save(offer);

      assertThat(decide(atHome(), new Intent.CancelProfileShare(offer.getId()))).isEqualTo(ALLOWED);

      var strangersOffer =
          shares.save(
              pendingShareBuilder()
                  .profileId(orphan.getId())
                  .householdId(UUID.randomUUID())
                  .build());
      var stranger = accounts.save(AccountFixture.defaultAccountBuilder().build());
      strangersOffer.setOfferedByAccountId(stranger.getId());
      shares.save(strangersOffer);
      assertThat(decide(atHome(), new Intent.CancelProfileShare(strangersOffer.getId())))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should authorize an active share end when the caller occupies an ADR-listed seat")
    void shouldAuthorizeActiveShareEndWhenCallerOccupiesAdrListedSeat() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var hosted =
          shares.save(
              activeShareBuilder()
                  .profileId(orphan.getId())
                  .householdId(account.getHouseholdId())
                  .build());

      // Target Household's live admin.
      assertThat(decide(atHome(), new Intent.EndProfileShare(hosted.getId()))).isEqualTo(ALLOWED);

      // A direct manager may end a local share, but not one into another Household.
      var elsewhere =
          shares.save(
              activeShareBuilder()
                  .profileId(orphan.getId())
                  .householdId(visitedHouseholdId)
                  .build());
      account.setHouseholdRole(HouseholdRole.MEMBER);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.EndProfileShare(elsewhere.getId()))).isEqualTo(DENIED);
      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(orphan.getId()).build());
      assertThat(decide(atHome(), new Intent.EndProfileShare(elsewhere.getId()))).isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.EndProfileShare(hosted.getId()))).isEqualTo(ALLOWED);

      // The sovereign Account over its own Personal Profile's visits.
      var visit =
          shares.save(
              activeShareBuilder()
                  .profileId(personal.getId())
                  .householdId(visitedHouseholdId)
                  .build());
      assertThat(decide(atHome(), new Intent.EndProfileShare(visit.getId()))).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny ordinary share termination by an unrelated ServerAdmin")
    void shouldDenyOrdinaryShareTerminationByUnrelatedServerAdmin() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var hosted =
          shares.save(
              activeShareBuilder()
                  .profileId(orphan.getId())
                  .householdId(visitedHouseholdId)
                  .build());
      account.setServerAdmin(true);
      accounts.save(account);

      assertThat(decide(atHome(), new Intent.EndProfileShare(hosted.getId()))).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should allow the administrative end only when the caller is a fresh ServerAdmin")
    void shouldAllowAdministrativelyEndOnlyWhenCallerIsFreshServerAdmin() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var hosted =
          shares.save(
              activeShareBuilder()
                  .profileId(orphan.getId())
                  .householdId(visitedHouseholdId)
                  .build());
      var administrativelyEnd = new Intent.AdministrativelyEndProfileShare(hosted.getId());

      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), administrativelyEnd))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), administrativelyEnd)).isEqualTo(REAUTHENTICATION_REQUIRED);
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), administrativelyEnd))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny ordinary and administrative end when the share is structural")
    void shouldDenyOrdinaryAndAdministrativelyEndWhenShareIsStructural() {
      var structural =
          shares
              .findByProfileIdAndHouseholdIdAndStatus(
                  personal.getId(), account.getHouseholdId(), ProfileShareStatus.ACTIVE)
              .orElseThrow();

      assertThat(decide(atHome(), new Intent.EndProfileShare(structural.getId())))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(
              decide(
                  withReauthenticatedAt(atHome(), Instant.now()),
                  new Intent.AdministrativelyEndProfileShare(structural.getId())))
          .isEqualTo(DENIED);
    }
  }

  private ProfileHouseholdShare.ProfileHouseholdShareBuilder<?, ?> pendingShareBuilder() {
    return ProfileHouseholdShare.builder().status(ProfileShareStatus.PENDING);
  }

  @Nested
  @DisplayName("Direct ProfileManagers")
  class Managers {

    @Test
    @DisplayName("Should allow a manager invitation only when the caller has durable management")
    void shouldAllowManagerInvitationOnlyWhenCallerHasDurableManagement() {
      assertThat(decide(atHome(), new Intent.InviteProfileManager(personal.getId())))
          .isEqualTo(ALLOWED);

      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      assertThat(decide(atHome(), new Intent.InviteProfileManager(orphan.getId())))
          .isEqualTo(DENIED);

      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(orphan.getId()).build());
      assertThat(decide(atHome(), new Intent.InviteProfileManager(orphan.getId())))
          .isEqualTo(ALLOWED);

      // A supervising HouseholdAdmin proposes while the restricted Profile is hosted with them.
      var kid = profiles.save(ProfileFixture.kidProfileBuilder().build());
      assertThat(decide(atHome(), new Intent.InviteProfileManager(kid.getId()))).isEqualTo(DENIED);
      shares.share(kid.getId(), account.getHouseholdId(), false);
      assertThat(decide(atHome(), new Intent.InviteProfileManager(kid.getId()))).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should allow invitation visibility when the caller is a manager or ServerAdmin")
    void shouldAllowInvitationVisibilityWhenCallerIsManagerOrServerAdmin() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var view = new Intent.ViewManagerInvitations(orphan.getId());
      assertThat(decide(atHome(), view)).isEqualTo(DENIED);

      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(orphan.getId()).build());
      assertThat(decide(atHome(), view)).isEqualTo(ALLOWED);
      managers.deleteAll();

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), view)).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow an invitation answer when the caller is the named recipient")
    void shouldAllowInvitationAnswerWhenCallerIsNamedRecipient() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var invitation = pendingManagerInvitation(orphan.getId(), account.getId());

      assertThat(decide(atHome(), new Intent.AcceptManagerInvitation(invitation.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.DeclineManagerInvitation(invitation.getId())))
          .isEqualTo(ALLOWED);

      // Consent is the recipient's alone: even ServerAdmin cannot answer for them.
      var someoneElse = pendingManagerInvitation(orphan.getId(), UUID.randomUUID());
      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.AcceptManagerInvitation(someoneElse.getId())))
          .isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.DeclineManagerInvitation(someoneElse.getId())))
          .isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.AcceptManagerInvitation(UUID.randomUUID())))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName(
        "Should allow invitation cancellation when the caller is the inviter or ServerAdmin")
    void shouldAllowInvitationCancellationWhenCallerIsInviterOrServerAdmin() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var strangers = pendingManagerInvitation(orphan.getId(), UUID.randomUUID());
      assertThat(decide(atHome(), new Intent.CancelManagerInvitation(strangers.getId())))
          .isEqualTo(DENIED);

      var mine = pendingManagerInvitation(orphan.getId(), UUID.randomUUID());
      mine.setInviterAccountId(account.getId());
      managerInvitations.save(mine);
      assertThat(decide(atHome(), new Intent.CancelManagerInvitation(mine.getId())))
          .isEqualTo(ALLOWED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.CancelManagerInvitation(strangers.getId())))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow relinquishment when the caller has a stored grant")
    void shouldAllowRelinquishmentWhenCallerHasStoredGrant() {
      // The sovereign self-manages without a row: nothing to relinquish.
      assertThat(decide(atHome(), new Intent.RelinquishProfileManagement(personal.getId())))
          .isEqualTo(DENIED);

      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(orphan.getId()).build());
      assertThat(decide(atHome(), new Intent.RelinquishProfileManagement(orphan.getId())))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow manager removal when the caller is the sovereign Account")
    void shouldAllowManagerRemovalWhenCallerIsSovereignAccount() {
      assertThat(decide(atHome(), new Intent.RemoveProfileManager(personal.getId())))
          .isEqualTo(ALLOWED);

      // A direct manager of someone else's Profile is a peer, and peers cannot remove peers.
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(orphan.getId()).build());
      assertThat(decide(atHome(), new Intent.RemoveProfileManager(orphan.getId())))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should require a fresh ServerAdmin when a manager is administratively granted")
    void shouldRequireFreshServerAdminWhenManagerIsAdministrativelyGranted() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var grant = new Intent.AdministrativelyGrantProfileManager(orphan.getId());

      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), grant)).isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), grant)).isEqualTo(REAUTHENTICATION_REQUIRED);
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), grant)).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should require a fresh ServerAdmin when a manager is administratively removed")
    void shouldRequireFreshServerAdminWhenManagerIsAdministrativelyRemoved() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var removal = new Intent.AdministrativelyRemoveProfileManager(orphan.getId());

      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), removal)).isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), removal)).isEqualTo(REAUTHENTICATION_REQUIRED);
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), removal))
          .isEqualTo(ALLOWED);
    }
  }

  private ProfileManagerInvitation pendingManagerInvitation(UUID profileId, UUID recipientId) {
    return managerInvitations.save(
        ProfileManagerInvitation.builder()
            .profileId(profileId)
            .profileName("Joe")
            .inviterAccountId(UUID.randomUUID())
            .inviterDisplayName("Inviter")
            .recipientAccountId(recipientId)
            .recipientEmail("recipient@example.com")
            .expiresAt(Instant.now().plusSeconds(3600))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
            .build());
  }

  @Nested
  @DisplayName("Devices")
  class Devices {

    @Test
    @DisplayName("Should forbid administration when the session is device-bound")
    void shouldForbidAdministrationWhenSessionDeviceBound() {
      account.setServerAdmin(true);
      accounts.save(account);
      var device = onDevice(withReauthenticatedAt(atHome(), Instant.now()));

      // Otherwise-allowed seats: the forbid alone flips each one.
      assertThat(decide(device, new Intent.AddLibrary())).isEqualTo(DENIED);
      assertThat(decide(device, new Intent.RenameAccount(account.getId()))).isEqualTo(DENIED);
      assertThat(decide(device, new Intent.ViewHouseholdAdministration(account.getHouseholdId())))
          .isEqualTo(DENIED);
      assertThat(decide(device, new Intent.RenameProfile(personal.getId()))).isEqualTo(DENIED);
      assertThat(decide(device, new Intent.IssueAccountInvitation())).isEqualTo(DENIED);
      assertThat(decide(device, new Intent.OfferProfileShare(personal.getId()))).isEqualTo(DENIED);
      assertThat(decide(device, new Intent.InviteProfileManager(personal.getId())))
          .isEqualTo(DENIED);
      assertThat(decide(device, new Intent.LinkDevice(UUID.randomUUID()))).isEqualTo(DENIED);

      // Watching is what a TV is for: picker and selection stay open.
      assertThat(decide(device, new Intent.ViewProfilePicker())).isEqualTo(ALLOWED);
      assertThat(decide(device, new Intent.SelectProfile(personal.getId(), false)))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow pairing approval when the Account is enabled")
    void shouldAllowPairingApprovalWhenAccountEnabled() {
      account.setHouseholdRole(HouseholdRole.MEMBER);
      accounts.save(account);
      assertThat(decide(member(), new Intent.LinkDevice(UUID.randomUUID()))).isEqualTo(ALLOWED);

      account.setEnabled(false);
      accounts.save(account);
      assertThat(decide(member(), new Intent.LinkDevice(UUID.randomUUID()))).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should allow revocation when the caller is the Household admin or ServerAdmin")
    void shouldAllowRevocationWhenCallerHouseholdAdminOrServerAdmin() {
      var registration =
          registrations.save(
              DeviceRegistration.builder()
                  .esn("esn-1")
                  .displayName("Living Room TV")
                  .householdId(account.getHouseholdId())
                  .authorizingAccountId(UUID.randomUUID())
                  .build());

      assertThat(decide(atHome(), new Intent.RevokeDeviceRegistration(registration.getId())))
          .isEqualTo(ALLOWED);

      account.setHouseholdRole(HouseholdRole.MEMBER);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.RevokeDeviceRegistration(registration.getId())))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.RevokeDeviceRegistration(registration.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.RevokeDeviceRegistration(UUID.randomUUID())))
          .isEqualTo(ALLOWED);

      // A registration that lost its Household proves nothing about any admin seat.
      account.setServerAdmin(false);
      account.setHouseholdRole(HouseholdRole.ADMIN);
      accounts.save(account);
      var orphaned =
          registrations.save(
              DeviceRegistration.builder()
                  .esn("esn-orphan")
                  .displayName("Detached TV")
                  .authorizingAccountId(UUID.randomUUID())
                  .build());
      assertThat(decide(atHome(), new Intent.RevokeDeviceRegistration(orphaned.getId())))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName(
        "Should allow Household ESN administration when the caller is a local admin or ServerAdmin")
    void shouldAllowHouseholdEsnAdministrationWhenCallerLocalAdminOrServerAdmin() {
      assertThat(decide(atHome(), new Intent.BlockEsn(account.getHouseholdId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.UnblockEsn(account.getHouseholdId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.ViewDeviceAdministration(account.getHouseholdId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.BlockEsn(visitedHouseholdId))).isEqualTo(DENIED);

      account.setServerAdmin(true);
      account.setHouseholdRole(HouseholdRole.MEMBER);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.BlockEsn(visitedHouseholdId))).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName(
        "Should allow the server-wide block when the ServerAdmin is freshly reauthenticated")
    void shouldAllowServerWideBlockWhenServerAdminFreshlyReauthenticated() {
      var block = new Intent.BlockEsnServerWide();

      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), block)).isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.UnblockEsnServerWide())).isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), block)).isEqualTo(REAUTHENTICATION_REQUIRED);
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), block)).isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.UnblockEsnServerWide())).isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.ViewServerDeviceAdministration())).isEqualTo(ALLOWED);
    }
  }

  @Nested
  @DisplayName("Transfers and deletion")
  class TransfersAndDeletion {

    @Test
    @DisplayName("Should require a live ServerAdmin when an Account or Profile is transferred")
    void shouldRequireLiveServerAdminWhenAccountOrProfileIsTransferred() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());

      assertThat(decide(atHome(), new Intent.TransferAccount(account.getId()))).isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.TransferProfile(orphan.getId()))).isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.TransferAccount(account.getId()))).isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.TransferProfile(orphan.getId()))).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny transfers when the ServerAdmin Account is disabled")
    void shouldDenyTransfersWhenServerAdminAccountIsDisabled() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      account.setServerAdmin(true);
      account.setEnabled(false);
      accounts.save(account);

      assertThat(decide(atHome(), new Intent.TransferAccount(account.getId()))).isEqualTo(DENIED);
      assertThat(decide(atHome(), new Intent.TransferProfile(orphan.getId()))).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should require a fresh ServerAdmin when an Account or Profile is deleted")
    void shouldRequireFreshServerAdminWhenAccountOrProfileIsDeleted() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var deleteAccount = new Intent.DeleteAccount(UUID.randomUUID());
      var administrativeDeletion = new Intent.AdministrativelyDeleteProfile(orphan.getId());

      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), deleteAccount))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), deleteAccount)).isEqualTo(REAUTHENTICATION_REQUIRED);
      assertThat(decide(atHome(), administrativeDeletion)).isEqualTo(REAUTHENTICATION_REQUIRED);
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), deleteAccount))
          .isEqualTo(ALLOWED);
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), administrativeDeletion))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny deletions when the ServerAdmin Account is disabled")
    void shouldDenyDeletionsWhenServerAdminAccountIsDisabled() {
      var orphan = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      account.setServerAdmin(true);
      account.setEnabled(false);
      accounts.save(account);
      var fresh = withReauthenticatedAt(atHome(), Instant.now());

      assertThat(decide(fresh, new Intent.DeleteAccount(UUID.randomUUID()))).isEqualTo(DENIED);
      assertThat(decide(fresh, new Intent.AdministrativelyDeleteProfile(orphan.getId())))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName(
        "Should require fresh reauthentication when an eligible person deletes their own Account")
    void shouldRequireFreshReauthenticationWhenEligiblePersonDeletesOwnAccount() {
      var selfDeletion = new Intent.DeleteMyAccount();

      assertThat(decide(atHome(), selfDeletion)).isEqualTo(REAUTHENTICATION_REQUIRED);
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), selfDeletion))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny self-deletion when the Personal Profile is restricted")
    void shouldDenySelfDeletionWhenPersonalProfileIsRestricted() {
      var selfDeletion = new Intent.DeleteMyAccount();

      personal.setMaximumAllowedRatingAge(12);
      profiles.save(personal);
      assertThat(decide(withReauthenticatedAt(atHome(), Instant.now()), selfDeletion))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should deny self-deletion when the Account is disabled")
    void shouldDenySelfDeletionWhenAccountIsDisabled() {
      account.setEnabled(false);
      accounts.save(account);

      assertThat(
              decide(withReauthenticatedAt(atHome(), Instant.now()), new Intent.DeleteMyAccount()))
          .isEqualTo(DENIED);
    }
  }

  private AuthenticatedIdentity withReauthenticatedAt(AuthenticatedIdentity base, Instant at) {
    return AuthenticatedIdentity.builder()
        .accountId(base.accountId())
        .authSessionId(base.authSessionId())
        .scope(base.scope())
        .householdId(base.householdId())
        .householdRole(base.householdRole())
        .contextHouseholdId(base.contextHouseholdId())
        .profileId(base.profileId())
        .reauthenticatedAt(Optional.of(at))
        .build();
  }

  private AuthenticatedIdentity onDevice(AuthenticatedIdentity base) {
    return AuthenticatedIdentity.builder()
        .accountId(base.accountId())
        .authSessionId(base.authSessionId())
        .scope(base.scope())
        .householdId(base.householdId())
        .householdRole(base.householdRole())
        .contextHouseholdId(base.contextHouseholdId())
        .profileId(base.profileId())
        .reauthenticatedAt(base.reauthenticatedAt())
        .registrationId(Optional.of(UUID.randomUUID()))
        .build();
  }

  private AuthenticatedIdentity atHome() {
    return identityBuilder().build();
  }

  private AuthenticatedIdentity member() {
    return identityBuilder().householdRole(HouseholdRole.MEMBER).build();
  }

  private AuthenticatedIdentity visiting() {
    return identityBuilder().contextHouseholdId(visitedHouseholdId).build();
  }

  private AuthenticatedIdentity watching(UUID profileId) {
    return AuthenticatedIdentity.builder()
        .accountId(account.getId())
        .authSessionId(session.getId())
        .scope(TokenScope.PLAYBACK)
        .householdId(account.getHouseholdId())
        .householdRole(HouseholdRole.ADMIN)
        .contextHouseholdId(account.getHouseholdId())
        .profileId(profileId)
        .streamSessionId(UUID.randomUUID())
        .build();
  }

  private Decision<AuthorizationUnit> decide(
      AuthenticatedIdentity identity, Intent.UnitIntent intent) {
    return authorizationService.decide(identity, intent);
  }

  private Decision<ProfilePolicyTransition> decide(
      AuthenticatedIdentity identity, Intent.ProfilePolicyChange intent) {
    return authorizationService.decide(identity, intent);
  }

  private AuthenticatedIdentity.AuthenticatedIdentityBuilder identityBuilder() {
    return AuthenticatedIdentity.builder()
        .accountId(account.getId())
        .authSessionId(session.getId())
        .scope(TokenScope.ACCOUNT)
        .householdId(account.getHouseholdId())
        .householdRole(HouseholdRole.ADMIN)
        .contextHouseholdId(account.getHouseholdId());
  }
}
