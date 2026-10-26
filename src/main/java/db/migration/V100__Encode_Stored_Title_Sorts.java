package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

@Slf4j
public class V100__Encode_Stored_Title_Sorts extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws SQLException {
    var connection = context.getConnection();
    var updatedCount = StoredTitleSorts.encodeAsSystemActor(connection);
    rebuildLetterCounts(connection);
    log.info("Title sort encoding migration completed: updated={}", updatedCount);
  }

  private void rebuildLetterCounts(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("DELETE FROM library_metadata");
      statement.execute(
          """
          INSERT INTO library_metadata (library_id, letter, item_count)
          SELECT item.library_id,
                 (CASE WHEN LEFT(item.title_sort, 1) COLLATE "C" ~ '[a-zA-Z]'
                       THEN UPPER(LEFT(item.title_sort, 1) COLLATE "C") ELSE 'HASH' END)
                     ::alphabet_letter,
                 COUNT(*)
          FROM base_collectable item
          LEFT JOIN movie ON movie.id = item.id
          LEFT JOIN series ON series.id = item.id
          WHERE movie.id IS NOT NULL OR series.id IS NOT NULL
          GROUP BY item.library_id, 2
          """);
    }
  }
}
