package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.RefreshTokenStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import java.util.Arrays;
import java.util.List;
import org.jooq.EnumType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Prevents the name-based jOOQ ↔ Java enum bridge from drifting. */
@Tag("UnitTest")
@DisplayName("Auth Enum Parity Tests")
class AuthEnumParityTest {

  @Test
  @DisplayName("Should keep session revocation reason literals in sync")
  void shouldKeepSessionRevocationReasonInSync() {
    assertParity(
        names(SessionRevocationReason.values()),
        literals(com.streamarr.server.jooq.generated.enums.SessionRevocationReason.values()));
  }

  @Test
  @DisplayName("Should keep refresh token status literals in sync")
  void shouldKeepRefreshTokenStatusInSync() {
    assertParity(
        names(RefreshTokenStatus.values()),
        literals(com.streamarr.server.jooq.generated.enums.RefreshTokenStatus.values()));
  }

  @Test
  @DisplayName("Should keep account role literals in sync")
  void shouldKeepAccountRoleInSync() {
    assertParity(
        names(AccountRole.values()),
        literals(com.streamarr.server.jooq.generated.enums.AccountRole.values()));
  }

  @Test
  @DisplayName("Should keep household role literals in sync")
  void shouldKeepHouseholdRoleInSync() {
    assertParity(
        names(HouseholdRole.values()),
        literals(com.streamarr.server.jooq.generated.enums.HouseholdRole.values()));
  }

  private static void assertParity(List<String> javaNames, List<String> databaseLiterals) {
    assertThat(javaNames).containsExactlyInAnyOrderElementsOf(databaseLiterals);
  }

  private static List<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).sorted().toList();
  }

  private static List<String> literals(EnumType[] values) {
    return Arrays.stream(values).map(EnumType::getLiteral).sorted().toList();
  }
}
