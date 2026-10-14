package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.jooq.generated.enums.HouseholdRole;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The System actor is not an account. Sessions, tokens, devices, Household membership and Profiles
 * all reference an account, so no row may take the System actor's id.
 */
@Tag("IntegrationTest")
@DisplayName("System Actor Account Integration Tests")
class SystemActorAccountIT extends AbstractIntegrationTest {

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("Should reject an account when its id is the System actor's id")
  void shouldRejectAccountWhenItsIdIsSystemActorId() {
    assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(_ -> insertSystemAccount()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_user_account_id_not_system_actor");
  }

  private void insertSystemAccount() {
    dsl.insertInto(USER_ACCOUNT)
        .set(USER_ACCOUNT.ID, SystemActor.ID)
        .set(USER_ACCOUNT.EMAIL, "system-" + UUID.randomUUID() + "@example.com")
        .set(USER_ACCOUNT.DISPLAY_NAME, "System")
        .set(USER_ACCOUNT.PASSWORD_HASH, "{noop}not-a-real-hash")
        .set(USER_ACCOUNT.HOUSEHOLD_ID, UUID.randomUUID())
        .set(USER_ACCOUNT.HOUSEHOLD_ROLE, HouseholdRole.ADMIN)
        .set(USER_ACCOUNT.PERSONAL_PROFILE_ID, UUID.randomUUID())
        .execute();
  }
}
