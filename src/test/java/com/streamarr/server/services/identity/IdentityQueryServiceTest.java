package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Identity Query Service Tests")
class IdentityQueryServiceTest {

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();

  private Household home;
  private Household visited;
  private UserAccount account;
  private Profile personal;
  private FakeAuthorizationService authorization;
  private IdentityQueryService service;

  @BeforeEach
  void setUp() {
    home = households.save(HouseholdFixture.defaultHouseholdBuilder().name("Home").build());
    visited = households.save(HouseholdFixture.defaultHouseholdBuilder().name("Grandma").build());
    account =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(home.getId())
                .householdRole(HouseholdRole.MEMBER)
                .serverAdmin(true)
                .build());
    personal =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .id(account.getPersonalProfileId())
                .householdId(home.getId())
                .name("Andrew")
                .build());
    shares.share(personal.getId(), home.getId(), true);
    shares.share(personal.getId(), visited.getId(), false);
    authorization = new FakeAuthorizationService(identity(home.getId(), null));
    service =
        new IdentityQueryService(
            accounts, households, profiles, authorization, new PaginationService());
  }

  @Test
  @DisplayName("Should describe the Account and Household context when Me view is requested")
  void shouldDescribeAccountAndHouseholdContextWhenMeViewIsRequested() {
    var me = service.meDetails(identity(home.getId(), personal.getId()));

    assertThat(me.account().getId()).isEqualTo(account.getId());
    assertThat(me.scope()).isEqualTo(TokenScope.PROFILE);
    assertThat(me.household().name()).isEqualTo("Home");
    assertThat(me.householdRole()).isEqualTo(HouseholdRole.MEMBER);
    assertThat(me.contextHousehold().name()).isEqualTo("Home");
    assertThat(me.deviceBound()).isFalse();
    assertThat(authorization.recordedIntents()).containsExactly(new Intent.ViewProfilePicker());
  }

  @Test
  @DisplayName("Should show the visited Household when that is the context")
  void shouldShowVisitedHouseholdWhenThatIsContext() {
    var me = service.meDetails(identity(visited.getId(), null));

    assertThat(me.contextHousehold().name()).isEqualTo("Grandma");
  }

  @Test
  @DisplayName("Should return the requested selectable Profiles when the first page is requested")
  void shouldReturnRequestedSelectableProfilesWhenFirstPageIsRequested() {
    var kid =
        profiles.save(
            ProfileFixture.kidProfileBuilder().householdId(home.getId()).name("Kai").build());
    shares.share(kid.getId(), home.getId(), false);
    var options =
        new KeysetPaginationOptions(
            null,
            PaginationOptions.builder()
                .paginationDirection(PaginationDirection.FORWARD)
                .cursor(Optional.empty())
                .limit(1)
                .build());

    var page = service.selectableProfiles(identity(home.getId(), null), options);

    assertThat(page.items()).extracting(item -> item.item().name()).containsExactly("Andrew");
    assertThat(page.hasNextPage()).isTrue();
    assertThat(page.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName("Should return the selected Profile only when requested")
  void shouldReturnSelectedProfileOnlyWhenRequested() {
    var kid =
        profiles.save(
            ProfileFixture.kidProfileBuilder().householdId(home.getId()).name("Kai").build());
    shares.share(kid.getId(), home.getId(), false);

    var selected = service.selectedProfile(identity(home.getId(), kid.getId()));

    assertThat(selected).map(IdentityQueryService.SelectableProfileDetails::name).contains("Kai");
  }

  @Test
  @DisplayName("Should return the requested usable Households when the first page is requested")
  void shouldReturnRequestedUsableHouseholdsWhenFirstPageIsRequested() {
    var options =
        new KeysetPaginationOptions(
            null,
            PaginationOptions.builder()
                .paginationDirection(PaginationDirection.FORWARD)
                .cursor(Optional.empty())
                .limit(1)
                .build());

    var page = service.usableHouseholds(identity(home.getId(), null), options);

    assertThat(page.items())
        .extracting(item -> item.item().household().name())
        .containsExactly("Home");
    assertThat(page.hasNextPage()).isTrue();
    assertThat(page.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName("Should fail closed when the picker is not allowed")
  void shouldFailClosedWhenPickerIsNotAllowed() {
    authorization.denyAll();
    var identity = identity(home.getId(), null);

    assertThatThrownBy(() -> service.meDetails(identity)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should fail closed when paged Profiles are not allowed")
  void shouldFailClosedWhenPagedProfilesAreNotAllowed() {
    authorization.denyAll();
    var options =
        new KeysetPaginationOptions(
            null,
            PaginationOptions.builder()
                .paginationDirection(PaginationDirection.FORWARD)
                .cursor(Optional.empty())
                .limit(1)
                .build());
    var identity = identity(home.getId(), null);

    assertThatThrownBy(() -> service.selectableProfiles(identity, options))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should read as unauthenticated when the Account or Household is unknown")
  void shouldReadAsUnauthenticatedWhenAccountOrHouseholdIsUnknown() {
    var ghost =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.ACCOUNT)
            .householdId(home.getId())
            .householdRole(HouseholdRole.MEMBER)
            .contextHouseholdId(home.getId())
            .build();
    var strangeContext = identity(UUID.randomUUID(), null);

    assertThatThrownBy(() -> service.meDetails(ghost))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(() -> service.meDetails(strangeContext))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  private AuthenticatedIdentity identity(UUID contextHouseholdId, UUID profileId) {
    return AuthenticatedIdentity.builder()
        .accountId(account.getId())
        .authSessionId(UUID.randomUUID())
        .scope(profileId == null ? TokenScope.ACCOUNT : TokenScope.PROFILE)
        .householdId(home.getId())
        .householdRole(HouseholdRole.MEMBER)
        .contextHouseholdId(contextHouseholdId)
        .profileId(profileId)
        .build();
  }
}
