package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.fakes.FakeAccountProfileRepository;
import com.streamarr.server.fakes.FakeHouseholdMembershipRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Identity Query Service Tests")
class IdentityQueryServiceTest {

  private final FakeUserAccountRepository userAccountRepository = new FakeUserAccountRepository();
  private final FakeHouseholdMembershipRepository membershipRepository =
      new FakeHouseholdMembershipRepository();
  private final FakeHouseholdRepository householdRepository = new FakeHouseholdRepository();
  private final FakeAccountProfileRepository accountProfileRepository =
      new FakeAccountProfileRepository(membershipRepository);
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();

  private final IdentityQueryService service =
      new IdentityQueryService(
          userAccountRepository,
          membershipRepository,
          householdRepository,
          accountProfileRepository,
          profileRepository);

  @Test
  @DisplayName("Should reject me view when account missing")
  void shouldRejectMeViewWhenAccountMissing() {
    var identity = identityFor(UUID.randomUUID(), null, TokenScope.ACCOUNT);

    assertThatThrownBy(() -> service.meView(identity))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should return account with no memberships when none exist")
  void shouldReturnAccountWithNoMembershipsWhenNoneExist() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var identity = identityFor(account.getId(), null, TokenScope.ACCOUNT);

    var view = service.meView(identity);

    assertThat(view.account().getId()).isEqualTo(account.getId());
    assertThat(view.scope()).isEqualTo(TokenScope.ACCOUNT);
    assertThat(view.memberships()).isEmpty();
  }

  @Test
  @DisplayName("Should mark the active profile within a membership")
  void shouldMarkTheActiveProfileWithinAMembership() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var householdId = saveHousehold("Smith Family");
    saveMembership(account.getId(), householdId, HouseholdRole.OWNER);
    var active = linkProfile(account.getId(), householdId);
    var other = linkProfile(account.getId(), householdId);

    var identity = identityFor(account.getId(), active.getId(), TokenScope.PROFILE);

    var view = service.meView(identity);

    assertThat(view.memberships()).singleElement();
    var membership = view.memberships().getFirst();
    assertThat(membership.householdName()).isEqualTo("Smith Family");
    assertThat(membership.householdRole()).isEqualTo(HouseholdRole.OWNER);
    assertThat(membership.profiles())
        .filteredOn(profile -> profile.id().equals(active.getId()))
        .singleElement()
        .satisfies(profile -> assertThat(profile.active()).isTrue());
    assertThat(membership.profiles())
        .filteredOn(profile -> profile.id().equals(other.getId()))
        .singleElement()
        .satisfies(profile -> assertThat(profile.active()).isFalse());
  }

  @Test
  @DisplayName("Should skip links whose profile row is gone")
  void shouldSkipLinksWhoseProfileRowIsGone() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var householdId = saveHousehold("Household");
    saveMembership(account.getId(), householdId, HouseholdRole.MEMBER);
    accountProfileRepository.save(
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(householdId)
            .profileId(UUID.randomUUID())
            .build());

    var view = service.meView(identityFor(account.getId(), null, TokenScope.HOUSEHOLD));

    assertThat(view.memberships().getFirst().profiles()).isEmpty();
  }

  @Test
  @DisplayName("Should reject me view when household row missing for membership")
  void shouldRejectMeViewWhenHouseholdRowMissingForMembership() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    saveMembership(account.getId(), UUID.randomUUID(), HouseholdRole.MEMBER);

    var identity = identityFor(account.getId(), null, TokenScope.ACCOUNT);

    assertThatThrownBy(() -> service.meView(identity))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  private AuthenticatedIdentity identityFor(UUID accountId, UUID profileId, TokenScope scope) {
    return AuthenticatedIdentity.builder()
        .accountId(accountId)
        .sessionId(UUID.randomUUID())
        .scope(scope)
        .profileId(profileId)
        .build();
  }

  private UUID saveHousehold(String name) {
    var household = HouseholdFixture.defaultHouseholdBuilder().name(name).build();
    household.setId(UUID.randomUUID());
    return householdRepository.save(household).getId();
  }

  private void saveMembership(UUID accountId, UUID householdId, HouseholdRole role) {
    membershipRepository.save(
        HouseholdMembership.builder()
            .accountId(accountId)
            .householdId(householdId)
            .householdRole(role)
            .build());
  }

  private Profile linkProfile(UUID accountId, UUID householdId) {
    var profile = ProfileFixture.defaultProfileBuilder().householdId(householdId).build();
    profile.setId(UUID.randomUUID());
    var saved = profileRepository.save(profile);
    accountProfileRepository.save(
        AccountProfile.builder()
            .accountId(accountId)
            .householdId(householdId)
            .profileId(saved.getId())
            .build());
    return saved;
  }
}
