package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Household Administration Service Tests")
class HouseholdAdministrationServiceTest {

  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final HouseholdAdministrationService service =
      new HouseholdAdministrationService(
          authorization,
          households,
          new MutationTransactions(
              new FakeTransactionManager(), new ConstraintViolationTranslator()));

  @Test
  @DisplayName("Should create a Household with a stripped name")
  void shouldCreateHouseholdWithStrippedName() {
    var outcome = service.createHousehold(authorization.currentIdentity(), "  Beach House  ");

    var created =
        outcome.fold(
            household -> household,
            _ -> {
              throw new AssertionError("expected acceptance");
            });
    assertThat(created.getName()).isEqualTo("Beach House");
    assertThat(households.findById(created.getId())).isPresent();
  }

  @Test
  @DisplayName("Should refuse creating a Household without a name")
  void shouldRefuseCreatingHouseholdWithoutName() {
    var outcome = service.createHousehold(authorization.currentIdentity(), "  ");

    assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
    assertThat(households.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should gate Household creation as a whole surface")
  void shouldGateHouseholdCreationAsWholeSurface() {
    var identity = authorization.currentIdentity();
    authorization.denyAll();

    assertThatThrownBy(() -> service.createHousehold(identity, "Beach House"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should rename a Household the caller administers")
  void shouldRenameHouseholdCallerAdministers() {
    var household = households.save(HouseholdFixture.defaultHouseholdBuilder().build());

    var outcome =
        service.renameHousehold(authorization.currentIdentity(), household.getId(), "New Name");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(households.findById(household.getId()).orElseThrow().getName())
        .isEqualTo("New Name");
  }

  @Test
  @DisplayName("Should read a hidden Household as not found and a visible one as forbidden")
  void shouldReadHiddenHouseholdAsNotFoundAndVisibleOneAsForbidden() {
    var household = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var identity = authorization.currentIdentity();
    var householdId = household.getId();

    authorization.denyAll();
    var hidden = service.renameHousehold(identity, householdId, "New Name");
    assertThat(hidden).isInstanceOf(Outcome.Rejected.class);

    authorization.decideWith(
        intent ->
            intent instanceof Intent.RenameHousehold
                ? new Decision.Denied<>(Decision.DenialReason.POLICY)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));
    assertThatThrownBy(() -> service.renameHousehold(identity, householdId, "New Name"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should fail closed when Household visibility cannot be decided")
  void shouldFailClosedWhenHouseholdVisibilityCannotBeDecided() {
    var household = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var identity = authorization.currentIdentity();
    var householdId = household.getId();
    authorization.decideWith(
        intent ->
            intent instanceof Intent.RenameHousehold
                ? new Decision.Denied<>(Decision.DenialReason.POLICY)
                : new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE));

    assertThatThrownBy(() -> service.renameHousehold(identity, householdId, "New Name"))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should refuse renaming without a name once authorized")
  void shouldRefuseRenamingWithoutNameOnceAuthorized() {
    var household = households.save(HouseholdFixture.defaultHouseholdBuilder().build());

    var outcome = service.renameHousehold(authorization.currentIdentity(), household.getId(), " ");

    assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
  }

  @Test
  @DisplayName("Should read an unknown Household as not found for an allowed caller")
  void shouldReadUnknownHouseholdAsNotFoundForAllowedCaller() {
    var outcome =
        service.renameHousehold(authorization.currentIdentity(), UUID.randomUUID(), "New Name");

    assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
  }
}
