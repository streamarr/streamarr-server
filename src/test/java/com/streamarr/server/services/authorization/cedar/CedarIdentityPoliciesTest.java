package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
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
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
                      new LivePrincipalHouseholdContributor(accounts))),
              ContributorStubs.systemClockFreshness(),
              new SimpleMeterRegistry()));

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
    shares.share(personal.getId(), account.getHouseholdId(), true);
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
    @DisplayName("Should allow ServerAdmin authority changes for a fresh, live, enabled admin")
    void shouldAllowServerAdminAuthorityChangesForFreshLiveEnabledAdmin() {
      account.setServerAdmin(true);
      accounts.save(account);
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());
      var fresh = withReauthenticatedAt(atHome(), Instant.now());

      assertThat(decide(fresh, new Intent.GrantServerAdmin(target.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(fresh, new Intent.RevokeServerAdmin(target.getId())))
          .isEqualTo(ALLOWED);
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
    @DisplayName("Should treat a stale or future-dated ceremony claim as not fresh")
    void shouldTreatStaleOrFutureDatedCeremonyClaimAsNotFresh() {
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
    @DisplayName("Should never misclassify a true authority denial as reauthentication required")
    void shouldNeverMisclassifyTrueAuthorityDenialAsReauthenticationRequired() {
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());
      var fresh = withReauthenticatedAt(atHome(), Instant.now());

      // Not a ServerAdmin: stale and fresh callers get the same ordinary policy denial.
      assertThat(decide(atHome(), new Intent.GrantServerAdmin(target.getId())))
          .isEqualTo(DENIED);
      assertThat(decide(fresh, new Intent.GrantServerAdmin(target.getId())))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should keep the policy denial when the admin Account is disabled")
    void shouldKeepPolicyDenialWhenAdminAccountIsDisabled() {
      account.setServerAdmin(true);
      account.setEnabled(false);
      accounts.save(account);
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());

      assertThat(decide(atHome(), new Intent.GrantServerAdmin(target.getId())))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should reserve account administration writes for a live ServerAdmin")
    void shouldReserveAccountAdministrationWritesForLiveServerAdmin() {
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());

      // A HouseholdAdmin is not enough — role changes are ServerAdmin work.
      assertThat(decide(atHome(), new Intent.GrantHouseholdAdmin(target.getId())))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.GrantHouseholdAdmin(target.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.RevokeHouseholdAdmin(target.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.DisableAccount(target.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.EnableAccount(target.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.CreateHousehold())).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny creating a Household to anyone but a live ServerAdmin")
    void shouldDenyCreatingHouseholdToAnyoneButLiveServerAdmin() {
      assertThat(decide(atHome(), new Intent.CreateHousehold())).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should let only a live HouseholdAdmin of that Household or ServerAdmin rename it")
    void shouldLetOnlyLiveHouseholdAdminOfThatHouseholdOrServerAdminRenameIt() {
      var home = account.getHouseholdId();

      assertThat(decide(atHome(), new Intent.RenameHousehold(home))).isEqualTo(ALLOWED);
      assertThat(decide(atHome(), new Intent.RenameHousehold(visitedHouseholdId)))
          .isEqualTo(DENIED);

      // The token says ADMIN, the live row says MEMBER: the live fact decides.
      account.setHouseholdRole(HouseholdRole.MEMBER);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.RenameHousehold(home))).isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(atHome(), new Intent.RenameHousehold(visitedHouseholdId)))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should let an Account rename itself and ServerAdmin rename anyone")
    void shouldLetAccountRenameItselfAndServerAdminRenameAnyone() {
      var target = accounts.save(AccountFixture.defaultAccountBuilder().build());

      // Self-targeted: principal and resource are one entity in the slice.
      assertThat(decide(member(), new Intent.RenameAccount(account.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decide(member(), new Intent.RenameAccount(target.getId())))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decide(member(), new Intent.RenameAccount(target.getId())))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny a disabled Account renaming itself")
    void shouldDenyDisabledAccountRenamingItself() {
      account.setEnabled(false);
      accounts.save(account);

      assertThat(decide(member(), new Intent.RenameAccount(account.getId())))
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
        .serverAdmin(base.serverAdmin())
        .contextHouseholdId(base.contextHouseholdId())
        .profileId(base.profileId())
        .reauthenticatedAt(at)
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
      AuthenticatedIdentity identity, Intent<AuthorizationUnit> intent) {
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
