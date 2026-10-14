package com.streamarr.server.repositories.audit;

import com.streamarr.server.domain.audit.AuditActor;
import com.streamarr.server.domain.auth.SystemActor;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

/**
 * Passes the actor to the audit trigger through the transaction-local setting {@value
 * #ACTOR_SETTING}. The trigger stores the setting's account id, and stores an empty actor for
 * {@value #REQUEST_WITHOUT_ACCOUNT}.
 */
@Repository
@RequiredArgsConstructor
public class PostgresTransactionAuditActor implements TransactionAuditActor {

  static final String ACTOR_SETTING = "streamarr.actor_id";
  static final String REQUEST_WITHOUT_ACCOUNT = "request-without-account";

  private final DSLContext dsl;

  @Override
  public void bindToCurrentTransaction(AuditActor actor) {
    dsl.select(
            DSL.function(
                DSL.name("set_config"),
                SQLDataType.VARCHAR,
                DSL.inline(ACTOR_SETTING),
                DSL.val(settingValue(actor)),
                DSL.inline(true)))
        .execute();
  }

  private static String settingValue(AuditActor actor) {
    return switch (actor) {
      case AuditActor.Account(var accountId) -> accountId.toString();
      case AuditActor.System _ -> SystemActor.ID.toString();
      case AuditActor.RequestWithoutAccount _ -> REQUEST_WITHOUT_ACCOUNT;
    };
  }
}
