package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.util.List;

public interface SecurityAuditEventRepository {

  void append(SecurityAuditEntry entry);

  /** The audit events beyond the seek, by occurrence time, then ID. */
  List<Row<SecurityAuditEventRecordView>> rowsBeyond(Seek seek);
}
