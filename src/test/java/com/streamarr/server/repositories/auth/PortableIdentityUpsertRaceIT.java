package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Portable Identity Upsert Race Integration Tests")
class PortableIdentityUpsertRaceIT extends AbstractIntegrationTest {

  @Autowired private DSLContext dsl;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository managerRepository;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;

  @Test
  @DisplayName("Should define invitation outcome when pending invitation changes concurrently")
  void shouldDefineInvitationOutcomeWhenPendingInvitationChangesConcurrently() throws Exception {
    var fixture = createInvitationFixture();
    var insertCompleted = new CountDownLatch(1);
    var transitionCompleted = new CountDownLatch(1);
    var listener =
        pauseAfterInsert("profile_manager_invitation", insertCompleted, transitionCompleted);
    var racingDsl = DSL.using(dsl.configuration().deriveAppending(listener));
    var racingRepository =
        new ProfileManagerInvitationRepositoryCustomImpl(racingDsl, Optional::empty);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var repeatedInvite =
          executor.submit(
              () ->
                  racingRepository.insertPendingIfAbsent(
                      fixture.profileId(),
                      fixture.invitingAccountId(),
                      fixture.invitedAccountId()));

      await(insertCompleted);
      jdbcTemplate.update(
          "UPDATE profile_manager_invitation SET status = CAST('REJECTED' AS profile_manager_invitation_status) WHERE id = ?",
          fixture.invitationId());
      transitionCompleted.countDown();

      var result = repeatedInvite.get(30, TimeUnit.SECONDS);
      var invitation = result.invitation();
      assertThat(result.inserted()).isTrue();
      assertThat(invitation.getId()).isNotEqualTo(fixture.invitationId());
      assertThat(invitation.getStatus()).isEqualTo(ProfileManagerInvitationStatus.PENDING);
      assertThat(invitationRepository.findById(invitation.getId())).isPresent();
    }
  }

  @Test
  @DisplayName("Should allow exactly one concurrent pending invitation transition")
  void shouldAllowExactlyOneConcurrentPendingInvitationTransition() throws Exception {
    var fixture = createInvitationFixture();
    var start = new CountDownLatch(1);

    List<Optional<ProfileManagerInvitation>> results;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var acceptance =
          executor.submit(
              () -> transitionAfter(start, fixture, ProfileManagerInvitationStatus.ACCEPTED));
      var cancellation =
          executor.submit(
              () -> transitionAfter(start, fixture, ProfileManagerInvitationStatus.CANCELED));
      start.countDown();
      results =
          List.of(acceptance.get(30, TimeUnit.SECONDS), cancellation.get(30, TimeUnit.SECONDS));
    }

    assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
    var finalStatus =
        invitationRepository.findById(fixture.invitationId()).orElseThrow().getStatus();
    var winningTransition = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
    assertThat(winningTransition.getStatus()).isEqualTo(finalStatus);
  }

  @Test
  @DisplayName("Should define share outcome when conflicting share is deleted concurrently")
  void shouldDefineShareOutcomeWhenConflictingShareIsDeletedConcurrently() throws Exception {
    var fixture = createShareFixture();
    var insertCompleted = new CountDownLatch(1);
    var transitionCompleted = new CountDownLatch(1);
    var listener =
        pauseAfterInsert("profile_household_share", insertCompleted, transitionCompleted);
    var racingDsl = DSL.using(dsl.configuration().deriveAppending(listener));
    var racingRepository =
        new ProfileHouseholdShareRepositoryCustomImpl(racingDsl, Optional::empty);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var repeatedOffer =
          executor.submit(
              () ->
                  racingRepository.insertPendingIfAbsent(
                      fixture.profileId(), fixture.householdId()));

      await(insertCompleted);
      jdbcTemplate.update("DELETE FROM profile_household_share WHERE id = ?", fixture.shareId());
      transitionCompleted.countDown();

      var result = repeatedOffer.get(30, TimeUnit.SECONDS);
      var share = result.share();
      assertThat(result.inserted()).isTrue();
      assertThat(share.getId()).isNotEqualTo(fixture.shareId());
      assertThat(share.getStatus()).isEqualTo(ProfileShareStatus.PENDING);
      assertThat(shareRepository.findById(share.getId())).isPresent();
    }
  }

  private InvitationFixture createInvitationFixture() {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var household =
                  householdRepository.save(
                      Household.builder().name("Invitation Race " + UUID.randomUUID()).build());
              var invitingAccount = accountRepository.save(account(household.getId(), true));
              var invitedAccount = accountRepository.save(account(household.getId(), false));
              var profile =
                  profileRepository.save(
                      Profile.builder()
                          .name("Invitation Race Profile " + UUID.randomUUID())
                          .kind(ProfileKind.ADULT)
                          .build());
              managerRepository.save(
                  ProfileManager.builder()
                      .profileId(profile.getId())
                      .accountId(invitingAccount.getId())
                      .build());
              var invitation =
                  invitationRepository.save(
                      ProfileManagerInvitation.builder()
                          .profileId(profile.getId())
                          .invitingAccountId(invitingAccount.getId())
                          .invitedAccountId(invitedAccount.getId())
                          .status(ProfileManagerInvitationStatus.PENDING)
                          .build());
              return new InvitationFixture(
                  profile.getId(),
                  invitingAccount.getId(),
                  invitedAccount.getId(),
                  invitation.getId());
            });
  }

  private Optional<ProfileManagerInvitation> transitionAfter(
      CountDownLatch start, InvitationFixture fixture, ProfileManagerInvitationStatus status) {
    await(start);
    return new TransactionTemplate(transactionManager)
        .execute(
            _ ->
                invitationRepository.transitionPending(
                    ProfileManagerInvitationTransition.builder()
                        .invitationId(fixture.invitationId())
                        .status(status)
                        .build()));
  }

  private ShareFixture createShareFixture() {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var managerHousehold =
                  householdRepository.save(
                      Household.builder().name("Share Race Manager " + UUID.randomUUID()).build());
              var targetHousehold =
                  householdRepository.save(
                      Household.builder().name("Share Race Target " + UUID.randomUUID()).build());
              var managerAccount = accountRepository.save(account(managerHousehold.getId(), true));
              accountRepository.save(account(targetHousehold.getId(), true));
              var profile =
                  profileRepository.save(
                      Profile.builder()
                          .name("Share Race Profile " + UUID.randomUUID())
                          .kind(ProfileKind.ADULT)
                          .build());
              managerRepository.save(
                  ProfileManager.builder()
                      .profileId(profile.getId())
                      .accountId(managerAccount.getId())
                      .build());
              var share =
                  shareRepository.save(
                      ProfileHouseholdShare.builder()
                          .profileId(profile.getId())
                          .householdId(targetHousehold.getId())
                          .status(ProfileShareStatus.PENDING)
                          .build());
              return new ShareFixture(profile.getId(), targetHousehold.getId(), share.getId());
            });
  }

  private UserAccount account(UUID householdId, boolean owner) {
    return UserAccount.builder()
        .email("upsert-race-" + UUID.randomUUID() + "@example.com")
        .displayName("Upsert Race Account")
        .passwordHash("encoded")
        .accountRole(AccountRole.USER)
        .homeHouseholdId(householdId)
        .householdRole(owner ? HouseholdRole.OWNER : HouseholdRole.PARENT)
        .build();
  }

  private ExecuteListener pauseAfterInsert(
      String tableName, CountDownLatch insertCompleted, CountDownLatch transitionCompleted) {
    var paused = new AtomicBoolean();
    return new ExecuteListener() {
      @Override
      public void executeEnd(ExecuteContext context) {
        var sql = context.sql();
        if (sql == null || !sql.contains(tableName) || !paused.compareAndSet(false, true)) {
          return;
        }
        insertCompleted.countDown();
        await(transitionCompleted);
      }
    };
  }

  private void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Upsert race was interrupted", exception);
    }
  }

  private record InvitationFixture(
      UUID profileId, UUID invitingAccountId, UUID invitedAccountId, UUID invitationId) {}

  private record ShareFixture(UUID profileId, UUID householdId, UUID shareId) {}
}
