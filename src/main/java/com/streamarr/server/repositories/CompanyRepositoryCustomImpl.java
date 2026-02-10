package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.Company.COMPANY;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class CompanyRepositoryCustomImpl implements CompanyRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public boolean insertOnConflictDoNothing(String sourceId, String name) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var rowsAffected =
        dsl.insertInto(COMPANY)
            .set(COMPANY.SOURCE_ID, sourceId)
            .set(COMPANY.NAME, name)
            .set(COMPANY.CREATED_BY, auditUser)
            .set(COMPANY.LAST_MODIFIED_BY, auditUser)
            .onConflict(COMPANY.SOURCE_ID)
            .doNothing()
            .execute();
    return rowsAffected > 0;
  }
}
