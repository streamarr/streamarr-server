package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PagedList;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import com.streamarr.server.services.pagination.ServerScope;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValueKind;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The server's security audit events, newest first. */
@Component
@RequiredArgsConstructor
public class SecurityAuditList implements PagedList<SecurityAuditEventRecordView, ServerScope> {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  private final SecurityAuditEventRepository securityAuditEventRepository;

  @Override
  public ListOrder order(ServerScope scope) {
    return NEWEST_FIRST;
  }

  @Override
  public List<Row<SecurityAuditEventRecordView>> rowsBeyond(ServerScope scope, Seek seek) {
    return securityAuditEventRepository.rowsBeyond(seek);
  }
}
