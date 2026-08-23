package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Administration Query Service Tests")
class AdministrationQueryServiceTest {

  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());
  private final AdministrationQueryService service =
      new AdministrationQueryService(
          authorization,
          new FakeHouseholdRepository(),
          new FakeUserAccountRepository(),
          new PaginationService(),
          new FakeProfileRepository());

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
}
