package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Registration Authorizer Service Tests")
class RegistrationAuthorizerServiceTest {

  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());
  private final RegistrationAuthorizerService service =
      new RegistrationAuthorizerService(authorization, accounts);

  private final UUID householdId = UUID.randomUUID();
  private UserAccount linker;
  private RegistrationAuthorizer authorizer;

  @BeforeEach
  void setUp() {
    linker = accounts.save(AccountFixture.defaultAccountBuilder().displayName("Morgan").build());
    authorizer = new RegistrationAuthorizer(householdId, linker.getId());
  }

  @Test
  @DisplayName("Should name the authorizer when the reader may list the Household's registrations")
  void shouldNameAuthorizerWhenReaderMayListHouseholdsRegistrations() {
    allowDevicesOf(householdId);

    assertThat(service.displayNames(reader(), Set.of(authorizer)))
        .containsEntry(authorizer, "Morgan");
  }

  @Test
  @DisplayName("Should name nobody when the reader may not list the Household's registrations")
  void shouldNameNobodyWhenReaderMayNotListHouseholdsRegistrations() {
    authorization.denyAll();

    assertThat(service.displayNames(reader(), Set.of(authorizer))).isEmpty();
  }

  @Test
  @DisplayName("Should name nobody when the authorizing Account is gone")
  void shouldNameNobodyWhenAuthorizingAccountIsGone() {
    allowDevicesOf(householdId);
    var gone = new RegistrationAuthorizer(householdId, UUID.randomUUID());

    assertThat(service.displayNames(reader(), Set.of(gone))).isEmpty();
  }

  @Test
  @DisplayName("Should decide each registration by its own Household when several are read")
  void shouldDecideEachRegistrationByItsOwnHouseholdWhenSeveralAreRead() {
    allowDevicesOf(householdId);
    var elsewhere = new RegistrationAuthorizer(UUID.randomUUID(), linker.getId());

    assertThat(service.displayNames(reader(), Set.of(authorizer, elsewhere)))
        .containsOnlyKeys(authorizer);
  }

  @Test
  @DisplayName("Should fail closed when the reader's authority cannot be decided")
  void shouldFailClosedWhenReadersAuthorityCannotBeDecided() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var reader = reader();
    var authorizers = Set.of(authorizer);

    assertThatThrownBy(() -> service.displayNames(reader, authorizers))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  private void allowDevicesOf(UUID household) {
    authorization.decideUnitWith(
        intent ->
            intent.equals(new Intent.ViewDeviceAdministration(household))
                ? new Decision.Allowed<>(AuthorizationUnit.INSTANCE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));
  }

  private AuthenticatedIdentity reader() {
    return authorization.currentIdentity();
  }
}
