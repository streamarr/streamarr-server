package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.services.identity.HouseholdDeletionService.SecurityAuditPageRequest;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@Isolated("Seeds the shared PostgreSQL security audit")
@DisplayName("Household Deletion Audit Integration Tests")
class HouseholdDeletionAuditIT extends AbstractIntegrationTest {
  private static final Instant BASE = Instant.parse("2026-08-01T12:00:00Z");
  @Autowired private HouseholdDeletionService service;
  @Autowired private AuthTestSupport auth;
  @Autowired private DSLContext dsl;
  @Autowired private SecurityAuditEventRepository audit;
  private AuthTestSupport.TestIdentity admin;

  @BeforeEach
  void setUp() {
    admin = auth.createAdminIdentity();
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    auth.deleteIdentity(admin);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("auditPages")
  @DisplayName(
      "Should return the requested audit window when seeking across times and identifier ties")
  void shouldReturnRequestedAuditWindowWhenSeekingAcrossTimesAndIdentifierTies(AuditWindow window) {
    if (!window.empty()) {
      seedAudit();
    }

    var page =
        service.securityAuditEvents(
            auth.identityOf(admin),
            SecurityAuditPageRequest.builder()
                .direction(window.direction())
                .limit(window.limit())
                .cursorId(window.cursor() == 0 ? null : id(window.cursor()))
                .cursorOccurredAt(window.cursor() == 0 ? null : time(window.cursor()))
                .build());

    assertThat(page.items())
        .extracting(item -> item.item().operation())
        .containsExactlyElementsOf(window.operations());
    assertThat(page.hasNextPage()).isEqualTo(window.next());
    assertThat(page.hasPreviousPage()).isEqualTo(window.previous());
  }

  @ParameterizedTest
  @EnumSource(PaginationDirection.class)
  @DisplayName("Should bound the database result when the audit contains more rows than requested")
  void shouldBoundDatabaseResultWhenAuditContainsMoreRowsThanRequested(
      PaginationDirection direction) {
    seedAudit();
    var rows =
        switch (direction) {
          case FORWARD -> audit.findNewestFirst(null, null, 2);
          case REVERSE -> audit.findOldestFirst(null, null, 2);
        };
    assertThat(rows)
        .extracting(row -> row.operation())
        .containsExactlyElementsOf(
            direction == PaginationDirection.FORWARD ? List.of("A", "B") : List.of("E", "D"));
  }

  private void seedAudit() {
    for (var ordinal = 1; ordinal <= 5; ordinal++) {
      dsl.insertInto(SECURITY_AUDIT_EVENT)
          .set(SECURITY_AUDIT_EVENT.ID, id(ordinal))
          .set(SECURITY_AUDIT_EVENT.OCCURRED_AT, time(ordinal).atOffset(ZoneOffset.UTC))
          .set(SECURITY_AUDIT_EVENT.OPERATION, Character.toString('F' - ordinal))
          .set(SECURITY_AUDIT_EVENT.OUTCOME, "SUCCESS")
          .execute();
    }
  }

  private static Stream<AuditWindow> auditPages() {
    return Stream.of(
        forward().label("forward zero with data").limit(0).next(true).build(),
        forward()
            .label("forward exact size")
            .limit(5)
            .operations(List.of("A", "B", "C", "D", "E"))
            .build(),
        forward()
            .label("forward lookahead")
            .limit(4)
            .operations(List.of("A", "B", "C", "D"))
            .next(true)
            .build(),
        forward()
            .label("forward excludes equal-time cursor and newer tie")
            .cursor(3)
            .limit(2)
            .operations(List.of("D", "E"))
            .previous(true)
            .build(),
        forward().label("forward empty terminal").cursor(1).limit(2).previous(true).build(),
        reverse()
            .label("reverse without cursor")
            .limit(2)
            .operations(List.of("D", "E"))
            .previous(true)
            .build(),
        reverse()
            .label("reverse excludes equal-time cursor and older tie")
            .cursor(3)
            .limit(2)
            .operations(List.of("A", "B"))
            .next(true)
            .build(),
        reverse()
            .label("reverse tie lookahead")
            .cursor(2)
            .limit(1)
            .operations(List.of("C"))
            .next(true)
            .previous(true)
            .build(),
        reverse().label("reverse empty terminal").cursor(5).limit(2).next(true).build(),
        reverse().label("reverse zero with data").limit(0).previous(true).build(),
        reverse()
            .label("reverse exact size")
            .limit(5)
            .operations(List.of("A", "B", "C", "D", "E"))
            .build(),
        forward().label("empty forward").empty(true).limit(2).build(),
        reverse().label("empty reverse").empty(true).limit(2).build(),
        forward().label("empty zero").empty(true).limit(0).build());
  }

  private static AuditWindow.AuditWindowBuilder forward() {
    return AuditWindow.builder().direction(PaginationDirection.FORWARD).operations(List.of());
  }

  private static AuditWindow.AuditWindowBuilder reverse() {
    return AuditWindow.builder().direction(PaginationDirection.REVERSE).operations(List.of());
  }

  private static UUID id(int ordinal) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(ordinal));
  }

  private static Instant time(int ordinal) {
    return switch (ordinal) {
      case 1 -> BASE;
      case 5 -> BASE.plusSeconds(2);
      default -> BASE.plusSeconds(1);
    };
  }

  @Builder
  private record AuditWindow(
      String label,
      PaginationDirection direction,
      int limit,
      int cursor,
      List<String> operations,
      boolean next,
      boolean previous,
      boolean empty) {
    @Override
    public String toString() {
      return label;
    }
  }
}
