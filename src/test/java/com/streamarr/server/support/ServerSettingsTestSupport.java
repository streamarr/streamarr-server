package com.streamarr.server.support;

import static com.streamarr.server.jooq.generated.tables.ServerSettings.SERVER_SETTINGS;

import com.streamarr.server.domain.streaming.EncoderPresetChoice;
import lombok.NonNull;
import org.jooq.DSLContext;

public final class ServerSettingsTestSupport {

  private ServerSettingsTestSupport() {}

  /** Puts the one server settings row back to the state that its migration seeds. */
  public static void resetServerSettings(@NonNull DSLContext dsl) {
    dsl.update(SERVER_SETTINGS)
        .set(SERVER_SETTINGS.ENCODER_PRESET, EncoderPresetChoice.AUTOMATIC.name())
        .setNull(SERVER_SETTINGS.LAST_MODIFIED_BY)
        .execute();
  }
}
