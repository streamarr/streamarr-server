package db.migration;

import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

// The letter index triggers from V105 move each re-encoded title to its new letter.
@Slf4j
public class V106__Strip_Leading_Punctuation_From_Stored_Title_Sorts extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws SQLException {
    var updatedCount = StoredTitleSorts.encodeAsSystemActor(context.getConnection());
    log.info("Leading punctuation title sort migration completed: updated={}", updatedCount);
  }
}
