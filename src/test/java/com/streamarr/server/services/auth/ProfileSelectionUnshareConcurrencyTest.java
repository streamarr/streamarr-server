package com.streamarr.server.services.auth;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Profile Selection and Unshare Concurrency Tests")
class ProfileSelectionUnshareConcurrencyTest {

  @Test
  @DisplayName("Should not select a profile after its concurrent household removal commits")
  void shouldNotSelectProfileAfterConcurrentHouseholdRemovalCommits() throws Exception {
    var clock = Clock.systemUTC();
    var accountRepository = new FakeUserAccountRepository();
    var shareRepository = new FakeProfileHouseholdShareRepository();
    var profileRepository = new FakeProfileRepository();
    var sessionRepository = new FakeAuthSessionRepository();
    var managerRepository = new FakeProfileManagerRepository();
    var invitationRepository = new FakeProfileManagerInvitationRepository();
    var auditService = new SecurityAuditService(new FakeSecurityAuditEventRepository());
    var safetyService = new HouseholdProfileSafetyService(shareRepository, profileRepository);
    var kidManagerPolicy =
        new KidProfileManagerPolicy(
            profileRepository, managerRepository, shareRepository, accountRepository);
    var managementService =
        new ProfileManagementService(
            managerRepository,
            invitationRepository,
            kidManagerPolicy,
            auditService,
            accountRepository,
            profileRepository,
            shareRepository,
            safetyService);
    var sharingService =
        new ProfileSharingService(
            managerRepository,
            shareRepository,
            profileRepository,
            managementService,
            safetyService,
            new AuthSessionProfileSelectionCleaner(sessionRepository, clock),
            kidManagerPolicy,
            auditService);
    var pausingEncoder = new PausingPasswordEncoder();
    var profileAvailabilityService =
        new ProfileAvailabilityService(shareRepository, profileRepository);
    var sessionScopeService =
        new SessionScopeService(
            profileAvailabilityService,
            sessionRepository,
            auditService,
            new ProfileEntryAuthorizer(
                new ProfilePinService(pausingEncoder),
                new CredentialGuessThrottle(
                    AuthThrottleProperties.builder()
                        .maxAttempts(3)
                        .window(Duration.ofMinutes(15))
                        .build(),
                    clock),
                auditService),
            new ProfileSelectionPersistenceService(sessionRepository, profileAvailabilityService),
            clock);
    var householdId = UUID.randomUUID();
    var account = saveOwner(accountRepository, householdId);
    var profile =
        profileRepository.save(Profile.builder().name("Protected").pinHash("2468").build());
    var share =
        shareRepository.save(
            ProfileHouseholdShare.builder()
                .profileId(profile.getId())
                .householdId(householdId)
                .status(ProfileShareStatus.ACTIVE)
                .build());
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    sessionRepository.registerAccountHome(account.getId(), householdId);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var selection =
          executor.submit(
              () ->
                  catchFailure(
                      () ->
                          sessionScopeService.selectProfile(
                              identity(account, session), profile.getId(), "2468")));
      assertThat(pausingEncoder.verificationStarted.await(5, SECONDS)).isTrue();

      sharingService.removeFromHousehold(
          HouseholdProfileRemoval.builder()
              .authority(identity(account, session))
              .shareId(share.getId())
              .build());
      pausingEncoder.allowVerificationToComplete.countDown();
      assertThat(selection.get(5, SECONDS)).isInstanceOf(ProfileAccessDeniedException.class);
    }

    assertThat(shareRepository.existsById(share.getId())).isFalse();
    assertThat(sessionRepository.findById(session.getId()).orElseThrow().getActiveProfileId())
        .isNull();
  }

  private UserAccount saveOwner(FakeUserAccountRepository accountRepository, UUID homeHouseholdId) {
    return accountRepository.save(
        UserAccount.builder()
            .email("owner-" + UUID.randomUUID() + "@example.com")
            .displayName("Owner")
            .passwordHash("{noop}not-a-real-hash")
            .accountRole(AccountRole.USER)
            .homeHouseholdId(homeHouseholdId)
            .householdRole(HouseholdRole.OWNER)
            .build());
  }

  private AuthenticatedIdentity identity(UserAccount account, AuthSession session) {
    return AuthenticatedIdentity.builder()
        .accountId(account.getId())
        .role(account.getAccountRole())
        .authSessionId(session.getId())
        .scope(TokenScope.ACCOUNT)
        .householdId(account.getHomeHouseholdId())
        .householdRole(account.getHouseholdRole())
        .build();
  }

  private Throwable catchFailure(Runnable operation) {
    try {
      operation.run();
      return null;
    } catch (RuntimeException exception) {
      return exception;
    }
  }

  private static final class PausingPasswordEncoder implements PasswordEncoder {

    private final CountDownLatch verificationStarted = new CountDownLatch(1);
    private final CountDownLatch allowVerificationToComplete = new CountDownLatch(1);

    @Override
    public String encode(CharSequence rawPassword) {
      return rawPassword.toString();
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      verificationStarted.countDown();
      try {
        if (!allowVerificationToComplete.await(5, SECONDS)) {
          throw new AssertionError("Timed out waiting to finish profile PIN verification");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while verifying the profile PIN", exception);
      }
      return NoOpPasswordEncoder.getInstance().matches(rawPassword, encodedPassword);
    }
  }
}
