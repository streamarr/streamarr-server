package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("User Account Repository Integration Tests")
class UserAccountRepositoryIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository repository;

  @Test
  @DisplayName("Should read no Accounts when no Households are requested")
  void shouldReadNoAccountsWhenNoHouseholdsAreRequested() {
    assertThat(repository.rowsBeyond(Map.of())).isEmpty();
  }
}
