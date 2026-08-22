package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("User Account Authority Facts Integration Tests")
class UserAccountAuthorityFactsIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private TransactionTemplate transactionTemplate;

  private UserAccount account;

  @AfterEach
  void deleteAccount() {
    if (account != null) {
      userAccountRepository.deleteById(account.getId());
    }
  }

  @Test
  @DisplayName("Should derive ServerAdmin authority from the admin account role")
  void shouldDeriveServerAdminAuthorityFromAdminAccountRole() {
    account = save(AccountRole.ADMIN, true);

    assertThat(userAccountRepository.findAuthorityFacts(account.getId()))
        .contains(new AccountAuthorityFacts(true, true));
  }

  @Test
  @DisplayName("Should report a disabled user account as neither enabled nor ServerAdmin")
  void shouldReportDisabledUserAccountAsNeitherEnabledNorServerAdmin() {
    account = save(AccountRole.USER, false);

    assertThat(userAccountRepository.findAuthorityFacts(account.getId()))
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
    account = save(AccountRole.ADMIN, true);

    var facts =
        transactionTemplate.execute(
            _ -> {
              // Load the entity into this transaction's persistence context first ...
              var managed = userAccountRepository.findById(account.getId()).orElseThrow();
              assertThat(managed.getAccountRole()).isEqualTo(AccountRole.ADMIN);
              // ... then demote it in a separate committed transaction.
              demoteInNewTransaction(account.getId());
              return userAccountRepository.findAuthorityFacts(account.getId());
            });

    assertThat(facts).contains(new AccountAuthorityFacts(true, false));
  }

  private void demoteInNewTransaction(UUID accountId) {
    var separate = new TransactionTemplate(transactionTemplate.getTransactionManager());
    separate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    separate.executeWithoutResult(
        _ -> {
          var demoted = userAccountRepository.findById(accountId).orElseThrow();
          demoted.setAccountRole(AccountRole.USER);
          userAccountRepository.saveAndFlush(demoted);
        });
  }

  private UserAccount save(AccountRole role, boolean enabled) {
    return userAccountRepository.saveAndFlush(
        AccountFixture.defaultAccountBuilder().accountRole(role).enabled(enabled).build());
  }
}
