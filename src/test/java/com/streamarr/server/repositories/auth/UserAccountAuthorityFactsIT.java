package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("User Account Authority Facts Integration Tests")
@Import(AuthTestSupportConfig.class)
class UserAccountAuthorityFactsIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;

  private AuthTestSupport.TestIdentity identity;

  @AfterEach
  void deleteIdentity() {
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should read ServerAdmin authority from the server_admin column")
  void shouldReadServerAdminAuthorityFromServerAdminColumn() {
    identity = authTestSupport.createAdminIdentity();

    assertThat(userAccountRepository.findAuthorityFacts(identity.account().getId()))
        .contains(new AccountAuthorityFacts(true, true));
  }

  @Test
  @DisplayName("Should report a disabled non-admin Account as neither enabled nor ServerAdmin")
  void shouldReportDisabledNonAdminAccountAsNeitherEnabledNorServerAdmin() {
    identity = authTestSupport.createIdentity();
    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);

    assertThat(userAccountRepository.findAuthorityFacts(identity.account().getId()))
        .contains(new AccountAuthorityFacts(false, false));
  }

  @Test
  @DisplayName("Should return empty when the account does not exist")
  void shouldReturnEmptyWhenAccountDoesNotExist() {
    assertThat(userAccountRepository.findAuthorityFacts(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("Should read the committed row even when a stale managed entity is loaded")
  void shouldReadCommittedRowEvenWhenStaleManagedEntityIsLoaded() {
    identity = authTestSupport.createAdminIdentity();
    var accountId = identity.account().getId();

    var facts =
        transactionTemplate.execute(
            _ -> {
              // Load the entity into this transaction's persistence context first ...
              var managed = userAccountRepository.findById(accountId).orElseThrow();
              assertThat(managed.isServerAdmin()).isTrue();
              // ... then revoke ServerAdmin in a separate committed transaction.
              revokeServerAdminInNewTransaction(accountId);
              return userAccountRepository.findAuthorityFacts(accountId);
            });

    assertThat(facts).contains(new AccountAuthorityFacts(true, false));
  }

  @Test
  @DisplayName("Should report Household access for members and visitors only")
  void shouldReportHouseholdAccessForMembersAndVisitorsOnly() {
    identity = authTestSupport.createIdentity();
    var accountId = identity.account().getId();

    assertThat(userAccountRepository.mayUseHousehold(accountId, identity.household().getId()))
        .isTrue();
    assertThat(userAccountRepository.mayUseHousehold(accountId, UUID.randomUUID())).isFalse();
    assertThat(userAccountRepository.findUsableHouseholdIds(accountId))
        .containsExactly(identity.household().getId());
  }

  private void revokeServerAdminInNewTransaction(UUID accountId) {
    var separate = new TransactionTemplate(transactionTemplate.getTransactionManager());
    separate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    separate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(accountId).orElseThrow();
          account.setServerAdmin(false);
          userAccountRepository.saveAndFlush(account);
        });
  }
}
