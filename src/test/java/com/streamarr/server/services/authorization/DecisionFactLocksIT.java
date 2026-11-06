package com.streamarr.server.services.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.identity.ProfileAdministrationService;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A write is decided inside the transaction that writes, and holds the relationship rows its
 * decision read until that transaction ends (ADR 0025); a read holds nothing. Another connection's
 * {@code FOR UPDATE NOWAIT} shows whether the deciding transaction still holds a row.
 */
@Tag("IntegrationTest")
@DisplayName("Decision Fact Locks Integration Tests")
class DecisionFactLocksIT extends AbstractIntegrationTest {

  private static final String LOCK_NOT_AVAILABLE = "55P03";
  private static final String LOCK_ACCOUNT_ROW =
      "SELECT 1 FROM user_account WHERE id = ? FOR UPDATE NOWAIT";
  private static final String LOCK_MANAGER_ROW =
      "SELECT 1 FROM profile_manager WHERE id = ? FOR UPDATE NOWAIT";

  @Autowired private AuthorizationService authorizationService;
  @Autowired private ProfileAdministrationService profileAdministrationService;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;

  private AuthTestSupport.TestIdentity serverAdmin;
  private AuthenticatedIdentity identity;

  @BeforeEach
  void createServerAdmin() {
    serverAdmin = authTestSupport.createAdminIdentity();
    identity = authTestSupport.identityOf(serverAdmin);
  }

  @AfterEach
  void deleteServerAdmin() {
    authTestSupport.deleteIdentity(serverAdmin);
  }

  @Test
  @DisplayName("Should hold the Account row until the transaction ends when a write is decided")
  void shouldHoldTheAccountRowUntilTheTransactionEndsWhenAWriteIsDecided() {
    var accountId = serverAdmin.account().getId();

    var heldWhileDeciding =
        decidedInsideTransaction(
            new Intent.CreateHousehold(), () -> refusesWriters(LOCK_ACCOUNT_ROW, accountId));

    assertThat(heldWhileDeciding).isTrue();
  }

  @Test
  @DisplayName("Should leave the Account row unlocked when a read is decided in a transaction")
  void shouldLeaveTheAccountRowUnlockedWhenAReadIsDecidedInATransaction() {
    var accountId = serverAdmin.account().getId();

    var heldWhileDeciding =
        decidedInsideTransaction(
            new Intent.ViewHouseholds(), () -> refusesWriters(LOCK_ACCOUNT_ROW, accountId));

    assertThat(heldWhileDeciding).isFalse();
  }

  @Test
  @DisplayName(
      "Should hold the direct manager grant until the transaction ends when a write is decided")
  void shouldHoldTheDirectManagerGrantUntilTheTransactionEndsWhenAWriteIsDecided() {
    var profile = createManagedProfile();
    var grantId = grantOf(profile);

    var heldWhileDeciding =
        decidedInsideTransaction(
            new Intent.RenameProfile(profile.getId()),
            () -> refusesWriters(LOCK_MANAGER_ROW, grantId));

    assertThat(heldWhileDeciding).isTrue();
  }

  @Test
  @DisplayName(
      "Should leave the direct manager grant unlocked when a read is decided in a transaction")
  void shouldLeaveTheDirectManagerGrantUnlockedWhenAReadIsDecidedInATransaction() {
    var profile = createManagedProfile();
    var grantId = grantOf(profile);

    var heldWhileDeciding =
        decidedInsideTransaction(
            new Intent.ViewProfileAdministration(profile.getId()),
            () -> refusesWriters(LOCK_MANAGER_ROW, grantId));

    assertThat(heldWhileDeciding).isFalse();
  }

  private boolean decidedInsideTransaction(
      Intent.UnitIntent intent, BooleanSupplier rowRefusesWriters) {
    return Boolean.TRUE.equals(
        transactionTemplate.execute(
            status -> {
              status.setRollbackOnly();
              assertThat(authorizationService.decide(identity, intent))
                  .isInstanceOf(Decision.Allowed.class);
              return rowRefusesWriters.getAsBoolean();
            }));
  }

  private boolean refusesWriters(String lockRowSql, UUID rowId) {
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(lockRowSql)) {
      statement.setObject(1, rowId);
      statement.executeQuery().close();
      return false;
    } catch (SQLException e) {
      assertThat(e.getSQLState()).isEqualTo(LOCK_NOT_AVAILABLE);
      return true;
    }
  }

  private Profile createManagedProfile() {
    var created =
        profileAdministrationService.createProfile(
            identity,
            ProfileAdministrationService.CreateProfileCommand.builder()
                .householdId(serverAdmin.household().getId())
                .name("Managed Profile")
                .build());
    return switch (created) {
      case Outcome.Accepted<Profile, ?>(var profile) -> profile;
      case Outcome.Rejected<Profile, ?>(var rejections) ->
          throw new AssertionError("Expected the Profile to be created but got: " + rejections);
    };
  }

  private UUID grantOf(Profile profile) {
    return profileManagerRepository.findByProfileId(profile.getId()).stream()
        .filter(grant -> grant.getAccountId().equals(serverAdmin.account().getId()))
        .findFirst()
        .orElseThrow()
        .getId();
  }
}
