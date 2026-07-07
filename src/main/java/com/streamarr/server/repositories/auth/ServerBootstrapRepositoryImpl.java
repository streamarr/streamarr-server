package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ServerBootstrapRepositoryImpl implements ServerBootstrapRepository {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public boolean claim(UUID adminAccountId) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);

    var rowsAffected =
        dsl.insertInto(SERVER_BOOTSTRAP)
            .set(SERVER_BOOTSTRAP.ID, true)
            .set(SERVER_BOOTSTRAP.ADMIN_ACCOUNT_ID, adminAccountId)
            .set(SERVER_BOOTSTRAP.CREATED_BY, auditUser)
            .set(SERVER_BOOTSTRAP.LAST_MODIFIED_BY, auditUser)
            .onConflict(SERVER_BOOTSTRAP.ID)
            .doNothing()
            .execute();

    return rowsAffected > 0;
  }

  @Override
  public boolean isClaimed() {
    return dsl.fetchExists(SERVER_BOOTSTRAP);
  }
}
