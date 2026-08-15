package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.graphql.inputs.PortableProfileInputs;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.CreatePortableProfileCommand;
import com.streamarr.server.services.auth.PortableIdentityService;
import com.streamarr.server.services.auth.ProfileManagementService;
import com.streamarr.server.services.auth.ProfilePinService;
import com.streamarr.server.services.auth.ProfilePolicyService;
import com.streamarr.server.services.auth.ResetProfilePinCommand;
import com.streamarr.server.services.auth.TokenScope;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Portable Profile Resolver Transaction Boundary Tests")
class PortableProfileResolverTransactionBoundaryTest {

  @Test
  @DisplayName("Should hash initial profile PIN before opening retried transaction")
  void shouldHashInitialProfilePinBeforeOpeningRetriedTransaction() {
    var accountId = UUID.randomUUID();
    var passwordEncoder = new TransactionObservingPasswordEncoder();
    var managementService = new CapturingProfileManagementService();
    var resolver =
        new PortableProfileResolver(
            authorizationService(accountId),
            portableIdentityService(new RecordingTransactionManager(), managementService, null),
            new ProfilePinService(passwordEncoder));

    resolver.createPortableProfile(
        new PortableProfileInputs.ProfileCreation("Kai", ProfileKind.KID, 7, "2468"));

    assertThat(passwordEncoder.encodedInsideTransaction).isFalse();
    assertThat(managementService.creation.pinHash()).isEqualTo("encoded-pin");
  }

  @Test
  @DisplayName("Should hash profile PIN before opening retried transaction")
  void shouldHashProfilePinBeforeOpeningRetriedTransaction() {
    var accountId = UUID.randomUUID();
    var passwordEncoder = new TransactionObservingPasswordEncoder();
    var policyService = new CapturingProfilePolicyService();
    var resolver =
        new PortableProfileResolver(
            authorizationService(accountId),
            portableIdentityService(new RecordingTransactionManager(), null, policyService),
            new ProfilePinService(passwordEncoder));

    resolver.resetProfilePin(
        new PortableProfileInputs.ProfilePinReset(UUID.randomUUID().toString(), "2468"));

    assertThat(passwordEncoder.encodedInsideTransaction).isFalse();
    assertThat(policyService.reset.pinHash()).isEqualTo("encoded-pin");
  }

  @Test
  @DisplayName("Should reject invalid identifiers before opening retried transaction")
  void shouldRejectInvalidIdentifiersBeforeOpeningRetriedTransaction() {
    var transactionManager = new RecordingTransactionManager();
    var resolver =
        new PortableProfileResolver(
            authorizationService(UUID.randomUUID()),
            portableIdentityService(transactionManager, null, null),
            null);
    var input = new PortableProfileInputs.ProfileKindChange("not-a-uuid", ProfileKind.KID);

    assertThatThrownBy(() -> resolver.setProfileKind(input)).isInstanceOf(InvalidIdException.class);
    assertThat(transactionManager.beginCount).isZero();
  }

  private static FakeAuthorizationService authorizationService(UUID accountId) {
    return new FakeAuthorizationService(
        AuthenticatedIdentity.builder()
            .accountId(accountId)
            .role(AccountRole.ADMIN)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.ACCOUNT)
            .build());
  }

  private static PortableIdentityService portableIdentityService(
      RecordingTransactionManager transactionManager,
      ProfileManagementService managementService,
      ProfilePolicyService policyService) {
    return PortableIdentityService.builder()
        .transactionTemplate(new TransactionTemplate(transactionManager))
        .managementService(managementService)
        .policyService(policyService)
        .build();
  }

  private static final class TransactionObservingPasswordEncoder implements PasswordEncoder {

    private boolean encodedInsideTransaction;

    @Override
    public String encode(CharSequence rawPassword) {
      encodedInsideTransaction = TransactionSynchronizationManager.isActualTransactionActive();
      return "encoded-pin";
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return false;
    }
  }

  private static final class CapturingProfilePolicyService extends ProfilePolicyService {

    private ResetProfilePinCommand reset;

    private CapturingProfilePolicyService() {
      super(null, null, null, null, null);
    }

    @Override
    public void resetPin(ResetProfilePinCommand command) {
      reset = command;
    }
  }

  private static final class CapturingProfileManagementService extends ProfileManagementService {

    private CreatePortableProfileCommand creation;

    private CapturingProfileManagementService() {
      super(null, null, null, null, null, null, null, null);
    }

    @Override
    public Profile create(CreatePortableProfileCommand command) {
      creation = command;
      return Profile.builder()
          .id(UUID.randomUUID())
          .name(command.name())
          .kind(command.kind())
          .maximumAllowedRatingAge(command.maximumAllowedRatingAge())
          .pinHash(command.pinHash())
          .build();
    }
  }

  private static final class RecordingTransactionManager
      extends AbstractPlatformTransactionManager {

    private int beginCount;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      beginCount++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      // Test transactions have no commit side effects.
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      // Test transactions have no rollback side effects.
    }
  }
}
