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

  private static final Comparator<SecurityAuditEventRecordView> NEWEST_FIRST =
      Comparator.comparing(SecurityAuditEventRecordView::occurredAt)
          .thenComparing(record -> record.id().toString())
          .reversed();
  private static final Comparator<SecurityAuditEventRecordView> OLDEST_FIRST =
      NEWEST_FIRST.reversed();

  private final List<SecurityAuditEntry> entries = new ArrayList<>();
  private final List<SecurityAuditEventRecordView> records = new ArrayList<>();

  @Override
  public void append(SecurityAuditEntry entry) {
    entries.add(entry);
    records.add(
        new SecurityAuditEventRecordView(
            UUID.randomUUID(),
            Instant.EPOCH.plusSeconds(records.size()),
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
        .sorted(NEWEST_FIRST)
        .filter(
            row ->
                beforeOccurredAt == null
                    || row.occurredAt().isBefore(beforeOccurredAt)
                    || row.occurredAt().equals(beforeOccurredAt)
                        && row.id().toString().compareTo(beforeId.toString()) < 0)
        .limit(limit)
        .toList();
  }

  @Override
  public List<SecurityAuditEventRecordView> pageOldestFirst(
      Instant afterOccurredAt, UUID afterId, int limit) {
    return records.stream()
        .sorted(OLDEST_FIRST)
        .filter(
            row ->
                afterOccurredAt == null
                    || row.occurredAt().isAfter(afterOccurredAt)
                    || row.occurredAt().equals(afterOccurredAt)
                        && row.id().toString().compareTo(afterId.toString()) > 0)
        .limit(limit)
        .toList();
  }

  public List<SecurityAuditEntry> entries() {
    return List.copyOf(entries);
  }
}
