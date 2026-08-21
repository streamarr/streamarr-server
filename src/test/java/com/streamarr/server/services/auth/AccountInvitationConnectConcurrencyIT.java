package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationMode;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccountInvitationCeremonyService.AcceptInvitationCommand;
import com.streamarr.server.services.auth.AccountInvitationCeremonyService.AcceptedInvitation;
import com.streamarr.server.services.identity.CredentialIssuanceService;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationCommand;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Account Invitation CONNECT Concurrency Integration Tests")
class AccountInvitationConnectConcurrencyIT extends AbstractIntegrationTest {

  private static final String FIRST_RIVAL_EMAIL = "connect-rival-one@example.com";
  private static final String SECOND_RIVAL_EMAIL = "connect-rival-two@example.com";
  private static final String SHARE_RACE_EMAIL = "connect-share-race@example.com";
  private static final String DUPLICATE_REOFFER_EMAIL = "connect-duplicate-reoffer@example.com";

  @Autowired private AccountInvitationCeremonyService ceremonyService;
  @Autowired private CredentialIssuanceService credentialIssuanceService;
  @Autowired private ProfileSharingService profileSharingService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository managerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private OpaqueCodes opaqueCodes;
  @Autowired private TransactionTemplate transactions;
  @Autowired private DataSource dataSource;

  private AuthTestSupport.TestIdentity sourceAdmin;
  private AuthTestSupport.TestIdentity targetAdmin;

  @BeforeEach
  void setUp() {
    sourceAdmin = authTestSupport.createAdminIdentity();
  }

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    invitationRepository.flush();
    deleteConnectedAccount(FIRST_RIVAL_EMAIL);
    deleteConnectedAccount(SECOND_RIVAL_EMAIL);
    deleteConnectedAccount(SHARE_RACE_EMAIL);
    deleteConnectedAccount(DUPLICATE_REOFFER_EMAIL);
    authTestSupport.deleteIdentity(sourceAdmin);
    if (targetAdmin != null) {
      authTestSupport.deleteIdentity(targetAdmin);
    }
  }

  @Test
  @DisplayName("Should reject one rival CONNECT acceptance as an invalid code")
  void shouldRejectOneRivalConnectAcceptanceAsInvalidCode() throws Exception {
    var orphan = orphanAtHome();
    var firstCode = pendingConnectInvitation(orphan, FIRST_RIVAL_EMAIL);
    var secondCode = pendingConnectInvitation(orphan, SECOND_RIVAL_EMAIL);
    var successes = new ArrayList<AcceptedInvitation>();
    var failures = new ArrayList<Throwable>();

    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      connection.setAutoCommit(false);
      statement.execute("LOCK TABLE user_account IN SHARE MODE");
      var first = executor.submit(() -> ceremonyService.accept(acceptCommand(firstCode)));
      var second = executor.submit(() -> ceremonyService.accept(acceptCommand(secondCode)));

      try {
        awaitAtMost(() -> waitingTableLockCount("user_account") >= 2);
      } finally {
        connection.rollback();
      }

      collect(first, successes, failures);
      collect(second, successes, failures);
    }

    assertThat(successes).hasSize(1);
    assertThat(failures).singleElement().isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @Test
  @DisplayName("Should end a share that activates concurrently with CONNECT acceptance")
  void shouldEndShareThatActivatesConcurrentlyWithConnectAcceptance() throws Exception {
    targetAdmin = authTestSupport.createIdentity();
    var orphan = orphanAtHome();
    var pending = pendingShare(orphan, targetAdmin.household().getId());
    var code = pendingConnectInvitation(orphan, SHARE_RACE_EMAIL);
    var targetIdentity = accountIdentity(targetAdmin);

    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT household_id FROM household_guard WHERE household_id = ? FOR UPDATE");
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      connection.setAutoCommit(false);
      statement.setObject(1, targetAdmin.household().getId());
      statement.executeQuery().close();

      var shareAcceptance =
          executor.submit(
              () -> profileSharingService.acceptProfileShare(targetIdentity, pending.getId()));
      Future<AcceptedInvitation> connectAcceptance;
      try {
        awaitAtMost(() -> waitingShareOperationCount() >= 1);
        connectAcceptance = executor.submit(() -> ceremonyService.accept(acceptCommand(code)));
        awaitAtMost(() -> waitingShareOperationCount() >= 2);
      } finally {
        connection.rollback();
      }

      var acceptedShare =
          shareAcceptance
              .get(15, TimeUnit.SECONDS)
              .fold(
                  share -> share,
                  rejections -> {
                    throw new AssertionError("expected acceptance but got " + rejections);
                  });
      assertThat(acceptedShare.getStatus()).isEqualTo(ProfileShareStatus.ACTIVE);
      assertThat(connectAcceptance.get(15, TimeUnit.SECONDS)).isNotNull();
    }

    assertThat(shareRepository.findById(pending.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.ENDED);
  }

  @Test
  @DisplayName("Should reoffer a Household once when its ID is requested twice")
  void shouldReofferHouseholdOnceWhenItsIdIsRequestedTwice() {
    targetAdmin = authTestSupport.createIdentity();
    var orphan = orphanAtHome();
    activeShare(orphan, targetAdmin.household().getId());
    var targetHouseholdId = targetAdmin.household().getId();
    var outcome =
        credentialIssuanceService.issueAccountInvitation(
            accountIdentity(sourceAdmin),
            IssueInvitationCommand.builder()
                .recipientEmail(DUPLICATE_REOFFER_EMAIL)
                .householdId(sourceAdmin.household().getId())
                .householdRole(HouseholdRole.MEMBER)
                .mode(AccountInvitationMode.CONNECT)
                .profileId(orphan.getId())
                .reofferHouseholdIds(List.of(targetHouseholdId, targetHouseholdId))
                .build());
    var code =
        outcome.fold(
            CredentialIssuanceService.IssuedInvitation::code,
            rejections -> {
              throw new AssertionError("expected issuance but got " + rejections);
            });

    ceremonyService.accept(acceptCommand(code));

    assertThat(shareRepository.findByProfileId(orphan.getId()))
        .filteredOn(share -> share.getHouseholdId().equals(targetHouseholdId))
        .extracting(ProfileHouseholdShare::getStatus)
        .containsExactlyInAnyOrder(ProfileShareStatus.ENDED, ProfileShareStatus.PENDING);
  }

  private Profile orphanAtHome() {
    return transactions.execute(
        _ -> {
          var orphan =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(sourceAdmin.household().getId())
                      .name("Grandpa Joe")
                      .build());
          managerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(sourceAdmin.account().getId())
                  .profileId(orphan.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(orphan.getId())
                  .householdId(sourceAdmin.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return orphan;
        });
  }

  private ProfileHouseholdShare pendingShare(Profile profile, UUID householdId) {
    return shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.PENDING)
            .offeredByAccountId(sourceAdmin.account().getId())
            .expiresAt(Instant.now().plus(Duration.ofDays(7)))
            .build());
  }

  private ProfileHouseholdShare activeShare(Profile profile, UUID householdId) {
    return shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
  }

  private String pendingConnectInvitation(Profile profile, String recipientEmail) {
    var issued = opaqueCodes.issue();
    transactions.executeWithoutResult(
        _ ->
            invitationRepository.saveAndFlush(
                AccountInvitation.builder()
                    .recipientEmail(recipientEmail)
                    .householdId(sourceAdmin.household().getId())
                    .householdName(sourceAdmin.household().getName())
                    .householdRole(HouseholdRole.MEMBER)
                    .mode(AccountInvitationMode.CONNECT)
                    .profileId(profile.getId())
                    .profileName(profile.getName())
                    .profileKind(ProfileKind.ADULT)
                    .issuerAccountId(sourceAdmin.account().getId())
                    .expiresAt(Instant.now().plus(Duration.ofDays(7)))
                    .publicId(issued.publicId())
                    .secretDigest(issued.digest())
                    .build()));
    return issued.code();
  }

  private static AcceptInvitationCommand acceptCommand(String code) {
    return AcceptInvitationCommand.builder()
        .code(code)
        .displayName("Joe")
        .password("a strong passphrase")
        .deviceName("web")
        .build();
  }

  private static AuthenticatedIdentity accountIdentity(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.builder()
        .accountId(identity.account().getId())
        .authSessionId(identity.session().getId())
        .scope(TokenScope.ACCOUNT)
        .householdId(identity.household().getId())
        .householdRole(identity.account().getHouseholdRole())
        .serverAdmin(identity.account().isServerAdmin())
        .contextHouseholdId(identity.household().getId())
        .build();
  }

  private void collect(
      Future<AcceptedInvitation> attempt,
      List<AcceptedInvitation> successes,
      List<Throwable> failures)
      throws Exception {
    try {
      successes.add(attempt.get(15, TimeUnit.SECONDS));
    } catch (ExecutionException exception) {
      failures.add(exception.getCause());
    }
  }

  private void deleteConnectedAccount(String email) {
    accountRepository
        .findByEmailIgnoreCase(email)
        .ifPresent(account -> authTestSupport.deleteAccount(account.getId()));
  }

  private void awaitAtMost(BooleanSupplier condition) {
    var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
    }
  }

  private int waitingTableLockCount(String relationName) {
    return queryCount(
        """
        SELECT count(*)
        FROM pg_locks AS lock
        JOIN pg_class AS relation ON relation.oid = lock.relation
        WHERE relation.relname = ?
          AND lock.mode = 'RowExclusiveLock'
          AND NOT lock.granted
        """,
        relationName);
  }

  private int waitingShareOperationCount() {
    return queryCount(
        """
        SELECT count(*)
        FROM pg_stat_activity
        WHERE pid <> pg_backend_pid()
          AND wait_event_type = 'Lock'
          AND query ILIKE '%profile_household_share%'
        """);
  }

  private int queryCount(String sql, Object... parameters) {
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql)) {
      for (var index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    } catch (Exception exception) {
      throw new AssertionError("could not inspect PostgreSQL lock state", exception);
    }
  }
}
