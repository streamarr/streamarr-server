package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("User Account Repository Integration Tests")
class UserAccountRepositoryIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository repository;
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("Should read no Accounts when no Households are requested")
  void shouldReadNoAccountsWhenNoHouseholdsAreRequested() {
    assertThat(repository.rowsBeyond(Map.of())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should turn a capital I with a dot into i and keep a dotless i when the database lowers an email")
  void shouldTurnCapitalIWithDotIntoIAndKeepDotlessIWhenDatabaseLowersEmail() {
    var lowered =
        dsl.select(DSL.lower(DSL.val("\u0130")), DSL.lower(DSL.val("\u0131"))).fetchSingle();

    assertThat(List.of(lowered.value1(), lowered.value2()))
        .as(
            "The email case tests in AuthEndpointsIT and CredentialCeremonyEndpointsIT rely on"
                + " this database's lower() turning U+0130 into i and keeping U+0131; its locale"
                + " or collation folds them differently")
        .containsExactly("i", "\u0131");
  }
}
