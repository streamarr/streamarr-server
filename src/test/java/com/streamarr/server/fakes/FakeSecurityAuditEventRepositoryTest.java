package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.SecurityAuditEntry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Fake Security Audit Event Repository Tests")
class FakeSecurityAuditEventRepositoryTest {

  private final FakeSecurityAuditEventRepository repository =
      new FakeSecurityAuditEventRepository();

  @Test
  @DisplayName(
      "Should return the most recently appended audit event first when reading the newest page")
  void shouldReturnMostRecentlyAppendedAuditEventFirstWhenReadingNewestPage() {
    repository.append(entry("first"));
    repository.append(entry("second"));

    assertThat(repository.pageNewestFirst(null, null, 10))
        .extracting(record -> record.operation())
        .containsExactly("second", "first");
  }

  @Test
  @DisplayName("Should include an equal-timestamp row when the cursor identifier sorts after it")
  void shouldIncludeEqualTimestampRowWhenCursorIdentifierSortsAfterIt() {
    repository.append(entry("same timestamp"));
    var record = repository.pageNewestFirst(null, null, 1).getFirst();
    var cursorAfterRecord = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    assertThat(repository.pageNewestFirst(record.occurredAt(), cursorAfterRecord, 10))
        .extracting(result -> result.id())
        .containsExactly(record.id());
  }

  private static SecurityAuditEntry entry(String operation) {
    return SecurityAuditEntry.builder()
        .actorAccountId(UUID.randomUUID())
        .operation(operation)
        .build();
  }
}
