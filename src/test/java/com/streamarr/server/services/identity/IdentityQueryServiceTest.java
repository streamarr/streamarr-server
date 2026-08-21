package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
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
    service = new IdentityQueryService(accounts, households, profiles, authorization);
  }

  @Test
  @DisplayName(
      "Should describe the Account, its Households, and the picker when Me view is requested")
  void shouldDescribeAccountHouseholdsAndPickerWhenMeViewIsRequested() {
    var kid =
        profiles.save(
            ProfileFixture.kidProfileBuilder()
                .householdId(home.getId())
                .name("Kai")
                .picture("kai.png")
                .pinHash("{argon2id}x")
                .build());
    shares.share(kid.getId(), home.getId(), false);

    var me = service.meView(identity(home.getId(), kid.getId()));

    assertThat(me.account().getId()).isEqualTo(account.getId());
    assertThat(me.scope()).isEqualTo(TokenScope.PROFILE);
    assertThat(me.household().name()).isEqualTo("Home");
    assertThat(me.householdRole()).isEqualTo(HouseholdRole.MEMBER);
    assertThat(me.contextHousehold().name()).isEqualTo("Home");
    assertThat(me.usableHouseholds())
        .extracting(
            view -> view.household().name(), IdentityQueryService.UsableHouseholdView::membership)
        .containsExactly(tuple("Home", true), tuple("Grandma", false));
    assertThat(me.selectableProfiles())
        .extracting(IdentityQueryService.SelectableProfileView::name)
        .containsExactly("Andrew", "Kai");
    var andrew = me.selectableProfiles().getFirst();
    assertThat(andrew.personal()).isTrue();
    assertThat(andrew.pinConfigured()).isFalse();
    assertThat(andrew.locked()).as("a Kid is present and Andrew has no PIN").isTrue();
    assertThat(andrew.selected()).isFalse();
    var kaiView = me.selectableProfiles().getLast();
    assertThat(kaiView.kind()).isEqualTo(ProfileKind.KID);
    assertThat(kaiView.picture()).contains("kai.png");
    assertThat(kaiView.pinConfigured()).isTrue();
    assertThat(kaiView.selected()).isTrue();
    assertThat(me.selectedProfile()).isEqualTo(kaiView);
    assertThat(me.deviceBound()).isFalse();
    assertThat(authorization.recordedIntents()).containsExactly(new Intent.ViewProfilePicker());
  }

  @Test
  @DisplayName("Should show the visited Household's picker when that is the context")
  void shouldShowVisitedHouseholdsPickerWhenThatIsContext() {
    var me = service.meView(identity(visited.getId(), null));

    assertThat(me.contextHousehold().name()).isEqualTo("Grandma");
    assertThat(me.selectableProfiles())
        .extracting(IdentityQueryService.SelectableProfileView::name)
        .containsExactly("Andrew");
    assertThat(me.selectedProfile()).isNull();
  }

  @Test
  @DisplayName("Should fail closed when the picker is not allowed")
  void shouldFailClosedWhenPickerIsNotAllowed() {
    authorization.denyAll();
    var identity = identity(home.getId(), null);

    assertThatThrownBy(() -> service.meView(identity)).isInstanceOf(AccessDeniedException.class);
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

    assertThatThrownBy(() -> service.meView(ghost))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(() -> service.meView(strangeContext))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  private AuthenticatedIdentity identity(UUID contextHouseholdId, UUID profileId) {
    return AuthenticatedIdentity.builder()
        .accountId(account.getId())
        .authSessionId(UUID.randomUUID())
        .scope(profileId == null ? TokenScope.ACCOUNT : TokenScope.PROFILE)
        .householdId(home.getId())
        .householdRole(HouseholdRole.MEMBER)
        .serverAdmin(true)
        .contextHouseholdId(contextHouseholdId)
        .profileId(profileId)
        .build();
  }
}
