package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class FakeSecurityAuditEventRepository implements SecurityAuditEventRepository {

  private final List<SecurityAuditEntry> entries = new ArrayList<>();
  private final List<SecurityAuditEventRecordView> records = new ArrayList<>();

  @Override
  public void append(SecurityAuditEntry entry) {
    entries.add(entry);
    records.add(
        new SecurityAuditEventRecordView(
            UUID.randomUUID(),
            Instant.now().minusSeconds(records.size()),
            entry.actorAccountId(),
            entry.operation(),
            "SUCCESS",
            entry.reason(),
            entry.resources().entrySet().stream()
                .map(resource -> "\"%s\": \"%s\"".formatted(resource.getKey(), resource.getValue()))
                .collect(Collectors.joining(", ", "{", "}"))));
  }

  @Override
  public List<SecurityAuditEventRecordView> pageNewestFirst(
      Instant beforeOccurredAt, UUID beforeId, int limit) {
    return records.stream()
        .sorted(Comparator.comparing(SecurityAuditEventRecordView::occurredAt).reversed())
        .filter(row -> beforeOccurredAt == null || row.occurredAt().isBefore(beforeOccurredAt))
        .limit(limit)
        .toList();
  }

  public List<SecurityAuditEntry> entries() {
    return List.copyOf(entries);
  }
}
