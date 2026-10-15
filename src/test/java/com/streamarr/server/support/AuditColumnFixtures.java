package com.streamarr.server.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Overwrites audit columns that a test arranges, such as timestamps for ordering or the empty
 * actors of rows written before the System actor existed. The audit trigger owns these columns, so
 * each overwrite runs in its own transaction with triggers switched off for that transaction.
 * Switching triggers off also skips foreign key checks, so only audit columns change.
 */
@RequiredArgsConstructor
public class AuditColumnFixtures {

  private final DSLContext dsl;
  private final TransactionTemplate transactionTemplate;

  public void overwriteCreatedOn(Table<?> table, Condition rows, Instant createdOn) {
    overwrite(table, rows, "created_on", createdOn);
  }

  public void overwriteLastModifiedOn(Table<?> table, Condition rows, Instant lastModifiedOn) {
    overwrite(table, rows, "last_modified_on", lastModifiedOn);
  }

  public void clearActors(Table<?> table, Condition rows) {
    overwriteWithTriggersOff(
        table,
        rows,
        Map.of(
            table.field("created_by", UUID.class), DSL.inline((UUID) null),
            table.field("last_modified_by", UUID.class), DSL.inline((UUID) null)));
  }

  private void overwrite(Table<?> table, Condition rows, String column, Instant value) {
    overwriteWithTriggersOff(
        table,
        rows,
        Map.of(table.field(column, OffsetDateTime.class), DSL.val(value.atOffset(ZoneOffset.UTC))));
  }

  private void overwriteWithTriggersOff(
      Table<?> table, Condition rows, Map<? extends Field<?>, ? extends Field<?>> values) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          dsl.execute("SET LOCAL session_replication_role = replica");
          var updated = dsl.update(table).set(values).where(rows).execute();
          if (updated == 0) {
            throw new IllegalStateException("No " + table.getName() + " row matches " + rows);
          }
        });
  }
}
