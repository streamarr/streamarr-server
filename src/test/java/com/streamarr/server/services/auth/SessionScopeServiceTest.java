package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.fakes.FakeAccountProfileRepository;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeHouseholdMembershipRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Session Scope Service Tests")
class SessionScopeServiceTest {

  private final FakeHouseholdMembershipRepository membershipRepository =
      new FakeHouseholdMembershipRepository();
  private final FakeAccountProfileRepository accountProfileRepository =
      new FakeAccountProfileRepository(membershipRepository);
  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeUserAccountRepository userAccountRepository = new FakeUserAccountRepository();

  private final SessionScopeService service =
      new SessionScopeService(
          membershipRepository, accountProfileRepository, sessionRepository, userAccountRepository);

  // --- autoSelectContext ---

  @Test
  @DisplayName("Should auto select household and profile when each is the only option")
  void shouldAutoSelectHouseholdAndProfileWhenEachIsTheOnlyOption() {
    var f = fixture();
    linkProfile(f);

    var context = service.autoSelectContext(f.account, f.session);

    assertThat(context.householdId()).isEqualTo(f.household.getId());
    assertThat(context.profileId()).isEqualTo(f.profile.getId());
    assertThat(reloadSession(f).getActiveProfileId()).isEqualTo(f.profile.getId());
  }

  @Test
  @DisplayName("Should auto select household only when multiple profiles selectable")
  void shouldAutoSelectHouseholdOnlyWhenMultipleProfilesSelectable() {
    var f = fixture();
    linkProfile(f);
    linkProfile(newProfile(f.household.getId()), f);

    var context = service.autoSelectContext(f.account, f.session);

    assertThat(context.householdId()).isEqualTo(f.household.getId());
    assertThat(context.profileId()).isNull();
    assertThat(reloadSession(f).getActiveProfileId()).isNull();
  }

  @Test
  @DisplayName("Should stay account scoped when membership is not unique")
  void shouldStayAccountScopedWhenMembershipIsNotUnique() {
    var f = fixture();
    saveMembership(f.account.getId(), UUID.randomUUID());

    var context = service.autoSelectContext(f.account, f.session);

    assertThat(context.householdId()).isNull();
    assertThat(context.profileId()).isNull();
  }

  // --- revalidateStoredContext ---

  @Test
  @DisplayName("Should keep profile scope when stored link still valid")
  void shouldKeepProfileScopeWhenStoredLinkStillValid() {
    var f = fixture();
    linkProfile(f);
    f.session.setActiveHouseholdId(f.household.getId());
    f.session.setActiveProfileId(f.profile.getId());

    var context = service.revalidateStoredContext(f.account, f.session);

    assertThat(context.householdId()).isEqualTo(f.household.getId());
    assertThat(context.profileId()).isEqualTo(f.profile.getId());
  }

  @Test
  @DisplayName("Should downgrade to household scope when profile link gone")
  void shouldDowngradeToHouseholdScopeWhenProfileLinkGone() {
    var f = fixture();
    f.session.setActiveHouseholdId(f.household.getId());
    f.session.setActiveProfileId(f.profile.getId());

    var context = service.revalidateStoredContext(f.account, f.session);

    assertThat(context.householdId()).isEqualTo(f.household.getId());
    assertThat(context.profileId()).isNull();
    assertThat(reloadSession(f).getActiveProfileId()).isNull();
    assertThat(reloadSession(f).getActiveHouseholdId()).isEqualTo(f.household.getId());
  }

  @Test
  @DisplayName("Should clear all selections when membership gone")
  void shouldClearAllSelectionsWhenMembershipGone() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = HouseholdFixture.defaultHouseholdBuilder().build();
    household.setId(UUID.randomUUID());
    var profile = newProfile(household.getId());
    var session =
        sessionRepository.save(
            AuthSession.builder()
                .accountId(account.getId())
                .activeHouseholdId(household.getId())
                .activeProfileId(profile.getId())
                .build());

    var context = service.revalidateStoredContext(account, session);

    assertThat(context.householdId()).isNull();
    assertThat(context.profileId()).isNull();
    var reloaded = sessionRepository.findById(session.getId()).orElseThrow();
    assertThat(reloaded.getActiveHouseholdId()).isNull();
  }

  @Test
  @DisplayName("Should stay account scoped when no household stored")
  void shouldStayAccountScopedWhenNoHouseholdStored() {
    var f = fixture();

    var context = service.revalidateStoredContext(f.account, f.session);

    assertThat(context.householdId()).isNull();
    assertThat(context.profileId()).isNull();
  }

  @Test
  @DisplayName("Should keep household scope when stored profile absent")
  void shouldKeepHouseholdScopeWhenStoredProfileAbsent() {
    var f = fixture();
    f.session.setActiveHouseholdId(f.household.getId());

    var context = service.revalidateStoredContext(f.account, f.session);

    assertThat(context.householdId()).isEqualTo(f.household.getId());
    assertThat(context.profileId()).isNull();
  }

  // --- selectHousehold ---

  @Test
  @DisplayName("Should reject household selection when account not member")
  void shouldRejectHouseholdSelectionWhenAccountNotMember() {
    var f = fixture();

    assertThatThrownBy(
            () -> service.selectHousehold(f.account.getId(), f.session.getId(), UUID.randomUUID()))
        .isInstanceOf(HouseholdAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should auto select sole profile when household selected")
  void shouldAutoSelectSoleProfileWhenHouseholdSelected() {
    var f = fixture();
    linkProfile(f);

    var context =
        service.selectHousehold(f.account.getId(), f.session.getId(), f.household.getId());

    assertThat(context.householdId()).isEqualTo(f.household.getId());
    assertThat(context.profileId()).isEqualTo(f.profile.getId());
  }

  @Test
  @DisplayName("Should clear profile when switching to household with multiple profiles")
  void shouldClearProfileWhenSwitchingToHouseholdWithMultipleProfiles() {
    var f = fixture();
    f.session.setActiveProfileId(UUID.randomUUID());
    sessionRepository.save(f.session);
    linkProfile(f);
    linkProfile(newProfile(f.household.getId()), f);

    var context =
        service.selectHousehold(f.account.getId(), f.session.getId(), f.household.getId());

    assertThat(context.householdId()).isEqualTo(f.household.getId());
    assertThat(context.profileId()).isNull();
  }

  // --- selectProfile ---

  @Test
  @DisplayName("Should require household before profile selection")
  void shouldRequireHouseholdBeforeProfileSelection() {
    var f = fixture();

    assertThatThrownBy(
            () -> service.selectProfile(f.account.getId(), f.session.getId(), UUID.randomUUID()))
        .isInstanceOf(HouseholdRequiredException.class);
  }

  @Test
  @DisplayName("Should reject profile selection when link missing")
  void shouldRejectProfileSelectionWhenLinkMissing() {
    var f = fixture();
    f.session.setActiveHouseholdId(f.household.getId());
    sessionRepository.save(f.session);

    assertThatThrownBy(
            () -> service.selectProfile(f.account.getId(), f.session.getId(), f.profile.getId()))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should select profile when link exists")
  void shouldSelectProfileWhenLinkExists() {
    var f = fixture();
    f.session.setActiveHouseholdId(f.household.getId());
    sessionRepository.save(f.session);
    linkProfile(f);

    var context = service.selectProfile(f.account.getId(), f.session.getId(), f.profile.getId());

    assertThat(context.profileId()).isEqualTo(f.profile.getId());
    assertThat(reloadSession(f).getActiveProfileId()).isEqualTo(f.profile.getId());
  }

  @Test
  @DisplayName("Should reject selection when account missing")
  void shouldRejectSelectionWhenAccountMissing() {
    var session =
        sessionRepository.save(AuthSession.builder().accountId(UUID.randomUUID()).build());

    assertThatThrownBy(
            () -> service.selectProfile(UUID.randomUUID(), session.getId(), UUID.randomUUID()))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should reject selection when session missing")
  void shouldRejectSelectionWhenSessionMissing() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());

    assertThatThrownBy(
            () -> service.selectProfile(account.getId(), UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  private record Fixture(
      UserAccount account,
      Household household,
      HouseholdMembership membership,
      Profile profile,
      AuthSession session) {}

  private Fixture fixture() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = HouseholdFixture.defaultHouseholdBuilder().build();
    household.setId(UUID.randomUUID());
    var membership = saveMembership(account.getId(), household.getId());
    var profile = newProfile(household.getId());
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    return new Fixture(account, household, membership, profile, session);
  }

  private AuthSession reloadSession(Fixture f) {
    return sessionRepository.findById(f.session.getId()).orElseThrow();
  }

  private HouseholdMembership saveMembership(UUID accountId, UUID householdId) {
    return membershipRepository.save(
        HouseholdMembership.builder()
            .accountId(accountId)
            .householdId(householdId)
            .householdRole(HouseholdRole.OWNER)
            .build());
  }

  private Profile newProfile(UUID householdId) {
    var profile = ProfileFixture.defaultProfileBuilder().householdId(householdId).build();
    profile.setId(UUID.randomUUID());
    return profile;
  }

  private void linkProfile(Fixture f) {
    linkProfile(f.profile, f);
  }

  private void linkProfile(Profile profile, Fixture f) {
    accountProfileRepository.save(
        AccountProfile.builder()
            .accountId(f.account.getId())
            .householdId(f.household.getId())
            .profileId(profile.getId())
            .build());
  }
}
