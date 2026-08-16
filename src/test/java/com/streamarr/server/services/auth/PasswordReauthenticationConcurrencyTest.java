package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeProfileDeletionAuthorizationRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Password Reauthentication Concurrency Tests")
class PasswordReauthenticationConcurrencyTest {

  private static final String CURRENT_PASSWORD = "correct horse battery staple";
  private static final String ROTATED_PASSWORD = "new password after reauthentication";

  private final PasswordEncoder delegate =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeProfileManagerRepository managerRepository = new FakeProfileManagerRepository();
  private final FakeProfileManagerInvitationRepository invitationRepository =
      new FakeProfileManagerInvitationRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeProfileDeletionAuthorizationRepository deletionAuthorizationRepository =
      new FakeProfileDeletionAuthorizationRepository();
  private final SecurityAuditService auditService =
      new SecurityAuditService(new FakeSecurityAuditEventRepository());

  @Test
  @DisplayName("Should verify a destructive-operation password before opening a transaction")
  void shouldVerifyDestructiveOperationPasswordBeforeOpeningTransaction() {
    var passwordEncoder = new TransactionObservingPasswordEncoder(delegate);
    var fixture = fixture(passwordEncoder);

    fixture.service().deleteProfile(fixture.command());

    assertThat(passwordEncoder.matchedInsideTransaction).isFalse();
  }

  @Test
  @DisplayName("Should allow deletion when credentials change after password verification")
  void shouldAllowDeletionWhenCredentialsChangeAfterPasswordVerification() {
    var account = saveAccount();
    var profile = saveManagedProfile(account);
    var passwordEncoder = new CredentialRotatingPasswordEncoder(delegate, account);
    var service = portableIdentityService(passwordEncoder);

    service.deleteProfile(deleteCommand(account, profile));

    assertThat(profileRepository.existsById(profile.getId())).isFalse();
  }

  private Fixture fixture(PasswordEncoder passwordEncoder) {
    var account = saveAccount();
    var profile = saveManagedProfile(account);
    return new Fixture(portableIdentityService(passwordEncoder), deleteCommand(account, profile));
  }

  private PortableIdentityService portableIdentityService(PasswordEncoder passwordEncoder) {
    var deletionService =
        new ProfileDeletionService(
            profileRepository,
            managerRepository,
            invitationRepository,
            shareRepository,
            accountRepository,
            deletionAuthorizationRepository,
            new AccountPasswordVerifier(
                passwordEncoder,
                new CredentialGuessThrottle(
                    AuthThrottleProperties.builder()
                        .maxAttempts(5)
                        .window(Duration.ofMinutes(15))
                        .build(),
                    Clock.systemUTC())),
            auditService);
    return PortableIdentityService.builder()
        .transactionTemplate(new TransactionTemplate(new NoOpTransactionManager()))
        .deletionService(deletionService)
        .build();
  }

  private UserAccount saveAccount() {
    return accountRepository.save(
        UserAccount.builder()
            .email("manager-" + UUID.randomUUID() + "@example.com")
            .displayName("Manager")
            .passwordHash(delegate.encode(CURRENT_PASSWORD))
            .accountRole(AccountRole.USER)
            .homeHouseholdId(UUID.randomUUID())
            .householdRole(HouseholdRole.OWNER)
            .build());
  }

  private Profile saveManagedProfile(UserAccount account) {
    var profile = profileRepository.save(Profile.builder().name("Ready To Delete").build());
    managerRepository.save(
        ProfileManager.builder().accountId(account.getId()).profileId(profile.getId()).build());
    return profile;
  }

  private DeleteProfileCommand deleteCommand(UserAccount account, Profile profile) {
    return DeleteProfileCommand.builder()
        .actingAccountId(account.getId())
        .profileId(profile.getId())
        .password(CURRENT_PASSWORD)
        .build();
  }

  private record Fixture(PortableIdentityService service, DeleteProfileCommand command) {}

  private static final class TransactionObservingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private boolean matchedInsideTransaction;

    private TransactionObservingPasswordEncoder(PasswordEncoder delegate) {
      this.delegate = delegate;
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      matchedInsideTransaction = TransactionSynchronizationManager.isActualTransactionActive();
      return delegate.matches(rawPassword, encodedPassword);
    }
  }

  private final class CredentialRotatingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final UserAccount account;

    private CredentialRotatingPasswordEncoder(PasswordEncoder delegate, UserAccount account) {
      this.delegate = delegate;
      this.account = account;
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      var matches = delegate.matches(rawPassword, encodedPassword);
      account.setPasswordHash(delegate.encode(ROTATED_PASSWORD));
      accountRepository.save(account);
      return matches;
    }
  }

  private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      Objects.requireNonNull(transaction);
      Objects.requireNonNull(definition);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      Objects.requireNonNull(status);
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      Objects.requireNonNull(status);
    }
  }
}
