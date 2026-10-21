package com.streamarr.server.fakes;

import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.SortValue;
import java.time.Instant;
import java.util.ArrayList;
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
  public List<Row<SecurityAuditEventRecordView>> rowsBeyond(Seek seek) {
    return InMemoryKeyset.rowsBeyond(
        records.stream()
            .map(
                event ->
                    new Row<>(
                        event,
                        new Position(new SortValue.OfInstant(event.occurredAt()), event.id())))
            .toList(),
        seek);
  }

  public List<SecurityAuditEntry> entries() {
    return List.copyOf(entries);
  }
}
