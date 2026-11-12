package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;

import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;

public final class MediaFileConditions {

  private MediaFileConditions() {}

  public static Condition hasFiles(Field<UUID> itemId) {
    var file = MEDIA_FILE.as("available_file");
    return exists(selectOne().from(file).where(file.MEDIA_ID.eq(itemId)));
  }
}
