package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.PaginationFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Administration Query Service Tests")
class AdministrationQueryServiceTest {

  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());
  private final FakeProfileRepository profiles = new FakeProfileRepository();
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final AdministrationQueryService service =
      AdministrationQueryService.builder()
          .authorizationService(authorization)
          .householdRepository(households)
          .userAccountRepository(new FakeUserAccountRepository())
          .paginationService(new PaginationService())
          .profileRepository(profiles)
          .accountInvitationRepository(new FakeAccountInvitationRepository())
          .householdsList(new HouseholdsList(households))
          .build();

  @Test
  @DisplayName("Should reject an Account cursor when its name is missing")
  void shouldRejectAccountCursorWhenItsNameIsMissing() {
    authorization.allowAll();
    var options =
        PaginationFixture.buildForwardOptions(10, MediaFilter.builder().build()).toBuilder()
            .cursorId(UUID.randomUUID())
            .build();
    var householdIds = Set.of(UUID.randomUUID());

    assertThatThrownBy(() -> service.accountPagesOf(householdIds, options))
        .isInstanceOf(InvalidPaginationCursorException.class)
        .hasMessage("Cursor sort value is required.");
  }

  @Test
  @DisplayName("Should fail closed when Household administration visibility cannot be decided")
  void shouldFailClosedWhenHouseholdAdministrationVisibilityCannotBeDecided() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var identity = authorization.currentIdentity();
    var householdId = UUID.randomUUID();

    assertThatThrownBy(() -> service.householdAdministration(identity, householdId))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should fail closed when Account administration visibility cannot be decided")
  void shouldFailClosedWhenAccountAdministrationVisibilityCannotBeDecided() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var identity = authorization.currentIdentity();
    var accountId = UUID.randomUUID();

    assertThatThrownBy(() -> service.accountAdministration(identity, accountId))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should fail closed when Profile administration visibility cannot be decided")
  void shouldFailClosedWhenProfileAdministrationVisibilityCannotBeDecided() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var identity = authorization.currentIdentity();
    var profileId = profiles.save(ProfileFixture.defaultProfileBuilder().build()).getId();

    assertThatThrownBy(() -> service.profileAdministration(identity, profileId))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should answer nothing when the administered Profile is unknown")
  void shouldAnswerNothingWhenAdministeredProfileIsUnknown() {
    authorization.allowAll();
    var identity = authorization.currentIdentity();

    assertThat(service.profileAdministration(identity, UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("Should forbid the invitation catalogue when the caller is denied")
  void shouldForbidInvitationCatalogueWhenCallerIsDenied() {
    authorization.denyAll();
    var identity = authorization.currentIdentity();
    var options = PaginationFixture.buildForwardOptions(10, MediaFilter.builder().build());

    assertThatThrownBy(() -> service.accountInvitations(identity, options))
        .isInstanceOf(AccessDeniedException.class);
  }
}
