package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SecurityAuditEventRepository {

  void append(SecurityAuditEntry entry);

  /**
   * One keyset page, newest first: rows strictly after the cursor in (occurred_at DESC, id DESC)
   * order. A null cursor starts at the newest row.
   */
  List<SecurityAuditEventRecordView> findNewestFirst(
      Instant beforeOccurredAt, UUID beforeId, int limit);

  /**
   * One reverse keyset page, oldest first for bounded fetching: rows strictly before the cursor in
   * the connection's (occurred_at DESC, id DESC) order. A null cursor starts at the oldest row.
   */
  List<SecurityAuditEventRecordView> findOldestFirst(
      Instant afterOccurredAt, UUID afterId, int limit);
}
