package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.jooq.EnumType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The jOOQ ↔ Java enum bridge resolves by name, so a Java enum value added without the matching
 * ALTER TYPE migration and jOOQ regen would fail at runtime (a write of that value — e.g. session
 * revocation — would throw). These pin every bridged pair so one-sided drift fails here instead.
 */
@Tag("UnitTest")
@DisplayName("Auth Enum Parity Tests")
class AuthEnumParityTest {

  @Test
  @DisplayName("Should keep session revocation reason literals in sync")
  void shouldKeepSessionRevocationReasonInSync() {
    assertParity(
        names(com.streamarr.server.domain.auth.SessionRevocationReason.values()),
        literals(com.streamarr.server.jooq.generated.enums.SessionRevocationReason.values()));
  }

  @Test
  @DisplayName("Should keep refresh token status literals in sync")
  void shouldKeepRefreshTokenStatusInSync() {
    assertParity(
        names(com.streamarr.server.domain.auth.RefreshTokenStatus.values()),
        literals(com.streamarr.server.jooq.generated.enums.RefreshTokenStatus.values()));
  }

  @Test
  @DisplayName("Should keep account role literals in sync")
  void shouldKeepAccountRoleInSync() {
    assertParity(
        names(com.streamarr.server.domain.auth.AccountRole.values()),
        literals(com.streamarr.server.jooq.generated.enums.AccountRole.values()));
  }

  @Test
  @DisplayName("Should keep household role literals in sync")
  void shouldKeepHouseholdRoleInSync() {
    assertParity(
        names(com.streamarr.server.domain.auth.HouseholdRole.values()),
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
