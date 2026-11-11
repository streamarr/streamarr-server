package com.streamarr.server.support;

import static com.streamarr.server.jooq.generated.tables.ServerSettings.SERVER_SETTINGS;

import com.streamarr.server.domain.audit.AuditActor;
import com.streamarr.server.domain.streaming.EncoderPresetChoice;
import com.streamarr.server.repositories.audit.PostgresTransactionAuditActorBinder;
import java.util.function.Consumer;
import lombok.NonNull;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class ServerSettingsTestSupport {

  private ServerSettingsTestSupport() {}

  /**
   * Puts the one server settings row back to the state that its migration seeds, with the System
   * actor as its last modifier.
   */
  public static void resetServerSettings(@NonNull DSLContext dsl) {
    asSystem(
        dsl,
        transaction ->
            transaction
                .update(SERVER_SETTINGS)
                .set(SERVER_SETTINGS.ENCODER_PRESET, EncoderPresetChoice.AUTOMATIC.name())
                .set(SERVER_SETTINGS.IDLE_SEGMENT_RETENTION_MINUTES, 60)
                .set(SERVER_SETTINGS.NOTIFICATION_RETENTION_DAYS, 90)
                .set(SERVER_SETTINGS.SHARE_CODE_LIFETIME_HOURS, 168)
                .set(SERVER_SETTINGS.INVITATION_LIFETIME_HOURS, 168)
                .set(SERVER_SETTINGS.SHARE_CODE_LIMIT, 10)
                .execute());
  }

  /** Stores how many live share codes a Household may hold, with the System actor as modifier. */
  public static void limitShareCodes(@NonNull DSLContext dsl, int codes) {
    asSystem(
        dsl,
        transaction ->
            transaction
                .update(SERVER_SETTINGS)
                .set(SERVER_SETTINGS.SHARE_CODE_LIMIT, codes)
                .execute());
  }

  private static void asSystem(DSLContext dsl, Consumer<DSLContext> write) {
    dsl.transaction(
        configuration -> {
          var transaction = DSL.using(configuration);
          new PostgresTransactionAuditActorBinder(transaction)
              .bindToCurrentTransaction(new AuditActor.System());
          write.accept(transaction);
        });
  }
}
