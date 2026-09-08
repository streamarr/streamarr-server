package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.SecurityAuditPageRequest;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;

@Tag("IntegrationTest")
@Isolated("Changes live PostgreSQL Account authority")
@Import(HouseholdDeletionAuthorizationIT.PolicyClockConfiguration.class)
@DisplayName("Household Deletion Authorization Integration Tests")
class HouseholdDeletionAuthorizationIT extends AbstractIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
  @Autowired private HouseholdDeletionService service;
  @Autowired private AuthTestSupport auth;
  @Autowired private UserAccountRepository accounts;
  @Autowired private HouseholdRepository households;
  @Autowired private SecurityAuditEventRepository audit;
  @Autowired private DSLContext dsl;
  private AuthTestSupport.TestIdentity keeper;
  private AuthTestSupport.TestIdentity candidate;

  @BeforeEach
  void setUp() {
    keeper = auth.createAdminIdentity();
    candidate = auth.createIdentity();
  }

  @AfterEach
  void tearDown() {
    auth.deleteIdentity(candidate);
    auth.deleteIdentity(keeper);
  }

  @ParameterizedTest
  @EnumSource(Caller.class)
  @DisplayName(
      "Should enforce live administrative authority and freshness when deleting a Household")
  void shouldEnforceLiveAdministrativeAuthorityAndFreshnessWhenDeletingHousehold(Caller caller) {
    applyAuthority(caller);
    var identity = identity(caller);
    var command =
        DeleteLastAccountAndHouseholdCommand.builder()
            .householdId(candidate.household().getId())
            .reason("authority check")
            .build();
    if (caller == Caller.HOUSEHOLD_ADMIN) {
      assertThatThrownBy(() -> service.deleteLastAccountAndHousehold(identity, command))
          .isInstanceOf(AccessDeniedException.class);
      assertThat(households.findById(candidate.household().getId())).isPresent();
      assertThat(auditsByCandidate()).isZero();
      return;
    }

    var outcome = service.deleteLastAccountAndHousehold(identity, command);
    var expected =
        switch (caller) {
          case FRESH_SERVER_ADMIN -> Outcome.accepted(candidate.household().getId());
          case MISSING_CEREMONY, EXPIRED_CEREMONY ->
              Outcome.rejected(new HouseholdDeletionRejections.ReauthenticationRequired());
          case DISABLED_SERVER_ADMIN ->
              Outcome.rejected(new HouseholdDeletionRejections.HouseholdNotFound());
          case HOUSEHOLD_ADMIN ->
              throw new AssertionError("HouseholdAdmin uses the access-denied channel");
        };
    assertThat(outcome).isEqualTo(expected);
    assertThat(households.existsById(candidate.household().getId()))
        .isEqualTo(caller != Caller.FRESH_SERVER_ADMIN);
    assertThat(households.findById(keeper.household().getId())).isPresent();
    assertThat(auditsByCandidate()).isEqualTo(caller == Caller.FRESH_SERVER_ADMIN ? 1 : 0);
  }

  @ParameterizedTest
  @EnumSource(
      value = Caller.class,
      names = {"FRESH_SERVER_ADMIN", "HOUSEHOLD_ADMIN", "DISABLED_SERVER_ADMIN"})
  @DisplayName(
      "Should enforce live audit authority without a ceremony when reading the security audit")
  void shouldEnforceLiveAuditAuthorityWithoutCeremonyWhenReadingSecurityAudit(Caller caller) {
    applyAuthority(caller);
    var identity = identity(Caller.MISSING_CEREMONY);
    var operation = "audit-access-" + candidate.account().getId();
    audit.append(
        SecurityAuditEntry.builder()
            .actorAccountId(keeper.account().getId())
            .operation(operation)
            .reason("access check")
            .resource("householdId", candidate.household().getId())
            .build());
    var request =
        SecurityAuditPageRequest.builder()
            .direction(PaginationDirection.FORWARD)
            .limit(100)
            .build();
    if (caller != Caller.FRESH_SERVER_ADMIN) {
      assertThatThrownBy(() -> service.securityAuditEvents(identity, request))
          .isInstanceOf(AccessDeniedException.class);
      return;
    }

    assertThat(service.securityAuditEvents(identity, request).items())
        .extracting(item -> item.item().operation())
        .contains(operation);
  }

  private void applyAuthority(Caller caller) {
    candidate.account().setServerAdmin(caller != Caller.HOUSEHOLD_ADMIN);
    candidate.account().setEnabled(caller != Caller.DISABLED_SERVER_ADMIN);
    accounts.saveAndFlush(candidate.account());
  }

  private AuthenticatedIdentity identity(Caller caller) {
    var confirmation =
        switch (caller) {
          case MISSING_CEREMONY -> Optional.<Instant>empty();
          case EXPIRED_CEREMONY -> Optional.of(NOW.minusSeconds(3600));
          default -> Optional.of(NOW);
        };
    return AuthenticatedIdentityFixture.accountScopedBuilder()
        .accountId(candidate.account().getId())
        .authSessionId(candidate.session().getId())
        .householdId(candidate.household().getId())
        .contextHouseholdId(candidate.household().getId())
        .householdRole(candidate.account().getHouseholdRole())
        .reauthenticatedAt(confirmation)
        .build();
  }

  private int auditsByCandidate() {
    return dsl.fetchCount(
        SECURITY_AUDIT_EVENT,
        SECURITY_AUDIT_EVENT.ACTOR_ACCOUNT_ID.eq(candidate.account().getId()));
  }

  private enum Caller {
    HOUSEHOLD_ADMIN,
    FRESH_SERVER_ADMIN,
    MISSING_CEREMONY,
    EXPIRED_CEREMONY,
    DISABLED_SERVER_ADMIN
  }

  @TestConfiguration
  static class PolicyClockConfiguration {
    @Bean
    @Primary
    Clock policyClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
