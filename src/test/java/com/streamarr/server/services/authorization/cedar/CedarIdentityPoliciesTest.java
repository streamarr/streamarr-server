package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManager;
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
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeProfileManagerRepository managers = new FakeProfileManagerRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();

  private final CedarAuthorizationDecider decider =
      new CedarAuthorizationDecider(
          new BasicAuthorizationEngine(),
          new CedarPolicyBundle(new BasicAuthorizationEngine()),
          new SliceAssembler(
              List.of(
                  new LivePrincipalAuthorityContributor(accounts),
                  new SignedPrincipalContextContributor(),
                  new ContextHouseholdAccessContributor(accounts),
                  new SessionLivenessContributor(sessions),
                  new ProfileAvailabilityContributor(profiles),
                  new ProfileManagementContributor(profiles, managers, shares, accounts),
                  new AccountHouseholdContributor(accounts))),
          new SimpleMeterRegistry());

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
    @DisplayName("Should allow the picker in the membership Household and deny it elsewhere")
    void shouldAllowPickerInMembershipHouseholdAndDenyItElsewhere() {
      assertThat(decider.decide(atHome(), new Intent.ViewProfilePicker())).isEqualTo(ALLOWED);
      assertThat(decider.decide(visiting(), new Intent.ViewProfilePicker())).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should allow the picker in a visited Household while the share is active")
    void shouldAllowPickerInVisitedHouseholdWhileShareIsActive() {
      shares.share(personal.getId(), visitedHouseholdId, false);

      assertThat(decider.decide(visiting(), new Intent.ViewProfilePicker())).isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow selecting an available unlocked Profile without a PIN")
    void shouldAllowSelectingAvailableUnlockedProfileWithoutPin() {
      assertThat(decider.decide(atHome(), new Intent.SelectProfile(personal.getId(), false)))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny selecting a Profile that is not available in the context")
    void shouldDenySelectingProfileNotAvailableInContext() {
      var elsewhere = profiles.save(ProfileFixture.defaultProfileBuilder().build());

      assertThat(decider.decide(atHome(), new Intent.SelectProfile(elsewhere.getId(), true)))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should deny selecting a Profile the safety rule locks")
    void shouldDenySelectingProfileSafetyRuleLocks() {
      var kid =
          profiles.save(
              ProfileFixture.kidProfileBuilder().householdId(account.getHouseholdId()).build());
      shares.share(kid.getId(), account.getHouseholdId(), false);

      assertThat(decider.decide(atHome(), new Intent.SelectProfile(personal.getId(), false)))
          .isEqualTo(DENIED);
      assertThat(decider.decide(atHome(), new Intent.SelectProfile(kid.getId(), false)))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should require the verified PIN when the Profile has one")
    void shouldRequireVerifiedPinWhenProfileHasOne() {
      personal.setPinHash("{argon2id}x");
      profiles.save(personal);

      assertThat(decider.decide(atHome(), new Intent.SelectProfile(personal.getId(), false)))
          .isEqualTo(DENIED);
      assertThat(decider.decide(atHome(), new Intent.SelectProfile(personal.getId(), true)))
          .isEqualTo(ALLOWED);
    }
  }

  @Nested
  @DisplayName("Playback")
  class Playback {

    @Test
    @DisplayName("Should allow playback while every live fact holds")
    void shouldAllowPlaybackWhileEveryLiveFactHolds() {
      assertThat(decider.decide(watching(personal.getId()), new Intent.Playback()))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny playback when the session is revoked")
    void shouldDenyPlaybackWhenSessionIsRevoked() {
      sessions.revoke(session.getId(), SessionRevocationReason.LOGOUT, Instant.now());

      assertThat(decider.decide(watching(personal.getId()), new Intent.Playback()))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should deny playback when the live session no longer selects the Profile")
    void shouldDenyPlaybackWhenLiveSessionNoLongerSelectsProfile() {
      var identity = watching(personal.getId());
      session.setSelectedProfileId(null);
      sessions.save(session);

      assertThat(decider.decide(identity, new Intent.Playback())).isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should deny playback when the Account is disabled")
    void shouldDenyPlaybackWhenAccountIsDisabled() {
      account.setEnabled(false);
      accounts.save(account);

      assertThat(decider.decide(watching(personal.getId()), new Intent.Playback()))
          .isEqualTo(DENIED);
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

      assertThat(decider.decide(identity, new Intent.Playback())).isEqualTo(DENIED);
      shares.share(managed.getId(), account.getHouseholdId(), false);
      assertThat(decider.decide(identity, new Intent.Playback())).isEqualTo(ALLOWED);
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

      assertThat(decider.decide(identity, new Intent.Playback())).isEqualTo(DENIED);
    }
  }

  @Nested
  @DisplayName("Reads")
  class Reads {

    @Test
    @DisplayName(
        "Should allow activity for the selected Profile, a manager, and a live ServerAdmin")
    void shouldAllowActivityForSelectedProfileManagerAndLiveServerAdmin() {
      var other = profiles.save(ProfileFixture.defaultProfileBuilder().build());

      assertThat(
              decider.decide(
                  watching(personal.getId()), new Intent.ViewProfileActivity(personal.getId())))
          .isEqualTo(ALLOWED);
      assertThat(
              decider.decide(
                  watching(personal.getId()), new Intent.ViewProfileActivity(other.getId())))
          .isEqualTo(DENIED);
      // Self-management of the unrestricted Adult Personal Profile counts as management.
      assertThat(decider.decide(atHome(), new Intent.ViewProfileActivity(personal.getId())))
          .isEqualTo(ALLOWED);

      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(other.getId()).build());
      assertThat(decider.decide(atHome(), new Intent.ViewProfileActivity(other.getId())))
          .isEqualTo(ALLOWED);

      managers.deleteAll();
      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decider.decide(atHome(), new Intent.ViewProfileActivity(other.getId())))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should deny activity of an unknown Profile")
    void shouldDenyActivityOfUnknownProfile() {
      assertThat(decider.decide(atHome(), new Intent.ViewProfileActivity(UUID.randomUUID())))
          .isEqualTo(DENIED);
    }

    @Test
    @DisplayName("Should allow Household administration to its HouseholdAdmin and ServerAdmin only")
    void shouldAllowHouseholdAdministrationToItsHouseholdAdminAndServerAdminOnly() {
      assertThat(
              decider.decide(
                  atHome(), new Intent.ViewHouseholdAdministration(account.getHouseholdId())))
          .isEqualTo(ALLOWED);
      assertThat(
              decider.decide(atHome(), new Intent.ViewHouseholdAdministration(visitedHouseholdId)))
          .isEqualTo(DENIED);
      assertThat(
              decider.decide(
                  member(), new Intent.ViewHouseholdAdministration(account.getHouseholdId())))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(
              decider.decide(member(), new Intent.ViewHouseholdAdministration(visitedHouseholdId)))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName(
        "Should allow Account administration to the Household's admins and ServerAdmin only")
    void shouldAllowAccountAdministrationToHouseholdsAdminsAndServerAdminOnly() {
      var neighbour =
          accounts.save(
              AccountFixture.defaultAccountBuilder().householdId(account.getHouseholdId()).build());
      var stranger = accounts.save(AccountFixture.defaultAccountBuilder().build());

      assertThat(decider.decide(atHome(), new Intent.ViewAccountAdministration(neighbour.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decider.decide(atHome(), new Intent.ViewAccountAdministration(stranger.getId())))
          .isEqualTo(DENIED);
      assertThat(decider.decide(atHome(), new Intent.ViewAccountAdministration(UUID.randomUUID())))
          .isEqualTo(DENIED);
      assertThat(decider.decide(member(), new Intent.ViewAccountAdministration(neighbour.getId())))
          .isEqualTo(DENIED);

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decider.decide(member(), new Intent.ViewAccountAdministration(stranger.getId())))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow a HouseholdAdmin to administer its own Account")
    void shouldAllowHouseholdAdminToAdministerOwnAccount() {
      assertThat(decider.decide(atHome(), new Intent.ViewAccountAdministration(account.getId())))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should allow Profile administration to managers, hosting admins, and ServerAdmin")
    void shouldAllowProfileAdministrationToManagersHostingAdminsAndServerAdmin() {
      var visitor = profiles.save(ProfileFixture.defaultProfileBuilder().build());

      assertThat(decider.decide(atHome(), new Intent.ViewProfileAdministration(visitor.getId())))
          .isEqualTo(DENIED);
      shares.share(visitor.getId(), account.getHouseholdId(), false);
      assertThat(decider.decide(atHome(), new Intent.ViewProfileAdministration(visitor.getId())))
          .isEqualTo(ALLOWED);
      assertThat(decider.decide(member(), new Intent.ViewProfileAdministration(visitor.getId())))
          .isEqualTo(DENIED);

      managers.save(
          ProfileManager.builder().accountId(account.getId()).profileId(visitor.getId()).build());
      assertThat(decider.decide(member(), new Intent.ViewProfileAdministration(visitor.getId())))
          .isEqualTo(ALLOWED);
      managers.deleteAll();

      account.setServerAdmin(true);
      accounts.save(account);
      assertThat(decider.decide(member(), new Intent.ViewProfileAdministration(UUID.randomUUID())))
          .isEqualTo(ALLOWED);
    }

    @Test
    @DisplayName("Should not treat a restricted Personal Profile as self-managed")
    void shouldNotTreatRestrictedPersonalProfileAsSelfManaged() {
      personal.setMaximumAllowedRatingAge(12);
      profiles.save(personal);

      assertThat(decider.decide(member(), new Intent.ViewProfileAdministration(personal.getId())))
          .isEqualTo(DENIED);
    }
  }

  private AuthenticatedIdentity atHome() {
    return identity(TokenScope.ACCOUNT, HouseholdRole.ADMIN, account.getHouseholdId(), null);
  }

  private AuthenticatedIdentity member() {
    return identity(TokenScope.ACCOUNT, HouseholdRole.MEMBER, account.getHouseholdId(), null);
  }

  private AuthenticatedIdentity visiting() {
    return identity(TokenScope.ACCOUNT, HouseholdRole.ADMIN, visitedHouseholdId, null);
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

  private AuthenticatedIdentity identity(
      TokenScope scope, HouseholdRole role, UUID contextHouseholdId, UUID profileId) {
    return AuthenticatedIdentity.builder()
        .accountId(account.getId())
        .authSessionId(session.getId())
        .scope(scope)
        .householdId(account.getHouseholdId())
        .householdRole(role)
        .contextHouseholdId(contextHouseholdId)
        .profileId(profileId)
        .build();
  }
}
