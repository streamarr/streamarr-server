package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Authentication Test Support Integration Tests")
class AuthTestSupportIT extends AbstractIntegrationTest {

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileHouseholdShareRepository profileShareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("Should delete an identity whose profile is shared with another household")
  void shouldDeleteIdentityWhoseProfileIsSharedWithAnotherHousehold() {
    var identity = authTestSupport.createIdentity();
    var otherIdentity = authTestSupport.createIdentity();
    saveActiveShare(identity, otherIdentity);

    try {
      assertThatCode(() -> authTestSupport.deleteIdentity(identity)).doesNotThrowAnyException();
    } finally {
      deleteActiveShare(identity, otherIdentity);
      if (userAccountRepository.existsById(identity.account().getId())) {
        authTestSupport.deleteIdentity(identity);
      }
      authTestSupport.deleteIdentity(otherIdentity);
    }
  }

  private void saveActiveShare(
      AuthTestSupport.TestIdentity identity, AuthTestSupport.TestIdentity otherIdentity) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ ->
                profileShareRepository.saveAndFlush(
                    ProfileHouseholdShare.builder()
                        .profileId(identity.profile().getId())
                        .householdId(otherIdentity.household().getId())
                        .status(ProfileShareStatus.ACTIVE)
                        .build()));
  }

  private void deleteActiveShare(
      AuthTestSupport.TestIdentity identity, AuthTestSupport.TestIdentity otherIdentity) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              profileShareRepository
                  .findByProfileIdAndHouseholdId(
                      identity.profile().getId(), otherIdentity.household().getId())
                  .ifPresent(profileShareRepository::delete);
              profileShareRepository.flush();
            });
  }
}
