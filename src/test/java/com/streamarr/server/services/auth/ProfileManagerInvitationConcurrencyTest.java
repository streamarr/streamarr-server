package com.streamarr.server.services.auth;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.exceptions.ProfileManagementDeniedException;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Profile Manager Invitation Concurrency Tests")
class ProfileManagerInvitationConcurrencyTest {

  @Test
  @DisplayName("Should not cancel an invitation after its concurrent acceptance commits")
  void shouldNotCancelInvitationAfterConcurrentAcceptanceCommits() throws Exception {
    var managerRepository = new PausingProfileManagerRepository();
    var invitationRepository = new FakeProfileManagerInvitationRepository();
    var profileRepository = new FakeProfileRepository();
    var shareRepository = new FakeProfileHouseholdShareRepository();
    var accountRepository = new FakeUserAccountRepository();
    var managementService =
        new ProfileManagementService(
            managerRepository,
            invitationRepository,
            new KidProfileManagerPolicy(
                profileRepository, managerRepository, shareRepository, accountRepository),
            new SecurityAuditService(new FakeSecurityAuditEventRepository()),
            accountRepository,
            profileRepository,
            shareRepository,
            new HouseholdProfileSafetyService(shareRepository, profileRepository));
    var service =
        PortableIdentityService.builder()
            .transactionTemplate(new TransactionTemplate(new NoOpTransactionManager()))
            .managementService(managementService)
            .build();
    var currentManagerId = UUID.randomUUID();
    var inviteeId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    managerRepository.save(
        ProfileManager.builder().accountId(currentManagerId).profileId(profileId).build());
    var invitation =
        invitationRepository.save(
            ProfileManagerInvitation.builder()
                .profileId(profileId)
                .invitingAccountId(currentManagerId)
                .invitedAccountId(inviteeId)
                .status(ProfileManagerInvitationStatus.PENDING)
                .build());
    managerRepository.pauseInvitee(inviteeId);

    Throwable cancellationFailure;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var acceptance =
          executor.submit(
              () ->
                  service.acceptProfileManagerInvitation(
                      ProfileManagerInvitationAcceptance.builder()
                          .actingAccountId(inviteeId)
                          .invitationId(invitation.getId())
                          .build()));
      assertThat(managerRepository.acceptanceReachedManagerInsert.await(5, SECONDS)).isTrue();

      var cancellation =
          executor.submit(
              () ->
                  catchFailure(
                      () ->
                          service.cancelProfileManagerInvitation(
                              ProfileManagerInvitationCancellation.builder()
                                  .actingAccountId(currentManagerId)
                                  .invitationId(invitation.getId())
                                  .build())));

      managerRepository.allowAcceptanceToContinue.countDown();
      acceptance.get(5, SECONDS);
      cancellationFailure = cancellation.get(5, SECONDS);
    }

    assertThat(cancellationFailure).isInstanceOf(ProfileManagementDeniedException.class);
    assertThat(managerRepository.existsByAccountIdAndProfileId(inviteeId, profileId)).isTrue();
    assertThat(invitationRepository.findById(invitation.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.ACCEPTED);
  }

  private static final class PausingProfileManagerRepository extends FakeProfileManagerRepository {

    private final CountDownLatch acceptanceReachedManagerInsert = new CountDownLatch(1);
    private final CountDownLatch allowAcceptanceToContinue = new CountDownLatch(1);
    private UUID pausedAccountId;

    private void pauseInvitee(UUID accountId) {
      pausedAccountId = accountId;
    }

    @Override
    public <S extends ProfileManager> S save(S entity) {
      if (entity.getAccountId().equals(pausedAccountId)) {
        acceptanceReachedManagerInsert.countDown();
        await(allowAcceptanceToContinue);
      }
      return super.save(entity);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, SECONDS)) {
        throw new AssertionError("Timed out coordinating concurrent invitation transitions");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(
          "Interrupted while coordinating concurrent invitation transitions", exception);
    }
  }

  private static Throwable catchFailure(Runnable operation) {
    try {
      operation.run();
      return null;
    } catch (RuntimeException exception) {
      return exception;
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
