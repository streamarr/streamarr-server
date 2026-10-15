package com.streamarr.server.repositories.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Checks the migrated schema, so a table added later cannot skip the audit trigger.
@Tag("IntegrationTest")
@DisplayName("Audit Column Catalog Integration Tests")
class AuditColumnCatalogIT extends AbstractIntegrationTest {

  // A table is audited when it stores an actor. Tables that keep only a creation time are not.
  private static final String AUDITED_TABLES =
      """
      SELECT c.oid, c.relname
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public'
        AND c.relkind IN ('r', 'p')
        AND EXISTS (
          SELECT 1 FROM pg_attribute a
          WHERE a.attrelid = c.oid
            AND NOT a.attisdropped
            AND a.attname IN ('created_by', 'last_modified_by'))
      """;

  // pg_trigger.tgtype bits, from PostgreSQL's catalog/pg_trigger.h.
  private static final int ROW = 1;
  private static final int BEFORE = 2;
  private static final int INSERT = 4;
  private static final int UPDATE = 16;

  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("Should carry the audit trigger when a table has audit columns")
  void shouldCarryTheAuditTriggerWhenATableHasAuditColumns() {
    var auditedTables =
        dsl.fetch("SELECT relname FROM (" + AUDITED_TABLES + ") audited")
            .getValues(0, String.class);

    var tablesMissingTheTrigger =
        dsl.fetch(
                """
                SELECT audited.relname
                FROM (%1$s) audited
                WHERE (
                    SELECT count(*) FROM pg_attribute a
                    WHERE a.attrelid = audited.oid
                      AND NOT a.attisdropped
                      AND a.attname IN
                        ('created_by', 'created_on', 'last_modified_by', 'last_modified_on')
                  ) <> 4
                  OR NOT EXISTS (
                    SELECT 1 FROM pg_trigger t
                    WHERE t.tgrelid = audited.oid
                      AND NOT t.tgisinternal
                      AND t.tgenabled <> 'D'
                      AND t.tgfoid = 'fill_audit_columns'::regproc
                      AND t.tgattr::text = ''
                      AND t.tgtype & %2$d = %2$d
                      AND t.tgtype & %3$d = %3$d
                      AND t.tgtype & %4$d = %4$d
                      AND t.tgtype & %5$d = %5$d)
                ORDER BY audited.relname
                """
                    .formatted(AUDITED_TABLES, ROW, BEFORE, INSERT, UPDATE))
            .getValues(0, String.class);

    assertThat(auditedTables).contains("genre", "session_progress", "server_bootstrap");
    assertThat(tablesMissingTheTrigger).isEmpty();
  }

  @Test
  @DisplayName("Should fire the audit trigger first when a table has other before-row triggers")
  void shouldFireTheAuditTriggerFirstWhenATableHasOtherBeforeRowTriggers() {
    // PostgreSQL fires a table's BEFORE ROW triggers in name order, compared byte by byte. The
    // audit trigger fires first, so a trigger that clears the modifier has the final word.
    var otherBeforeRowUpdateTriggers =
        beforeRowUpdateTriggersOnAuditedTables("t.tgfoid <> 'fill_audit_columns'::regproc");
    var triggersFiringBeforeTheAuditTrigger =
        beforeRowUpdateTriggersOnAuditedTables(
            "t.tgfoid <> 'fill_audit_columns'::regproc"
                + " AND t.tgname COLLATE \"C\" < 'fill_audit_columns' COLLATE \"C\"");

    assertThat(otherBeforeRowUpdateTriggers)
        .contains("invalidate_account_invitation_when_target_disappears");
    assertThat(triggersFiringBeforeTheAuditTrigger).isEmpty();
  }

  @Test
  @DisplayName("Should touch the collectable when a joined subtype table changes")
  void shouldTouchTheCollectableWhenAJoinedSubtypeTableChanges() {
    var subtypeTables = joinedSubtypeTables("TRUE");
    var subtypeTablesMissingTheTrigger =
        joinedSubtypeTables(
            """
            NOT EXISTS (
              SELECT 1 FROM pg_trigger t
              WHERE t.tgrelid = child.oid
                AND NOT t.tgisinternal
                AND t.tgenabled <> 'D'
                AND t.tgfoid = 'touch_collectable_when_subtype_changes'::regproc
                AND t.tgtype & %1$d = %1$d
                AND t.tgtype & %2$d = 0
                AND t.tgtype & %3$d = %3$d)
            """
                .formatted(ROW, BEFORE, UPDATE));

    assertThat(subtypeTables).contains("movie", "series");
    assertThat(subtypeTablesMissingTheTrigger).isEmpty();
  }

  // JOINED inheritance: a subtype table's primary key is a foreign key to base_collectable.
  private List<String> joinedSubtypeTables(String condition) {
    return dsl.fetch(
            """
            SELECT child.relname
            FROM pg_constraint fk
            JOIN pg_class child ON child.oid = fk.conrelid
            JOIN pg_constraint pk ON pk.conrelid = fk.conrelid AND pk.contype = 'p'
            WHERE fk.contype = 'f'
              AND fk.confrelid = 'base_collectable'::regclass
              AND fk.conkey = pk.conkey
              AND %s
            """
                .formatted(condition))
        .getValues(0, String.class);
  }

  private List<String> beforeRowUpdateTriggersOnAuditedTables(String condition) {
    return dsl.fetch(
            """
            SELECT t.tgname
            FROM pg_trigger t
            JOIN (%1$s) audited ON audited.oid = t.tgrelid
            WHERE NOT t.tgisinternal
              AND t.tgtype & %2$d = %2$d
              AND t.tgtype & %3$d = %3$d
              AND t.tgtype & %4$d = %4$d
              AND %5$s
            """
                .formatted(AUDITED_TABLES, ROW, BEFORE, UPDATE, condition))
        .getValues(0, String.class);
  }
}
