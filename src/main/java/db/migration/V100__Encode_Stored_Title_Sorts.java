package db.migration;

import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.utils.TitleSortUtil;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

@Slf4j
public class V100__Encode_Stored_Title_Sorts extends BaseJavaMigration {

  private static final int BATCH_SIZE = 1000;

  @Override
  public void migrate(Context context) throws SQLException {
    var connection = context.getConnection();
    nameSystemActor(connection);
    var updatedCount = encodeStoredTitleSorts(connection);
    rebuildLetterCounts(connection);
    log.info("Title sort encoding migration completed: updated={}", updatedCount);
  }

  private void nameSystemActor(Connection connection) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT set_config('streamarr.actor_id', ?, true)")) {
      statement.setString(1, SystemActor.ID.toString());
      statement.execute();
    }
  }

  private int encodeStoredTitleSorts(Connection connection) throws SQLException {
    try (var select = connection.createStatement();
        var update =
            connection.prepareStatement(
                "UPDATE base_collectable SET title_sort = ? WHERE id = ?")) {
      select.setFetchSize(BATCH_SIZE);
      var updatedCount = 0;
      try (var rows =
          select.executeQuery(
              "SELECT id, title, title_sort FROM base_collectable"
                  + " WHERE title IS NOT NULL AND title_sort IS NOT NULL")) {
        while (rows.next()) {
          var added = addUpdateWhenEncodingChanges(rows, update);
          updatedCount += added;
          if (added > 0 && updatedCount % BATCH_SIZE == 0) {
            update.executeBatch();
          }
        }
      }

      update.executeBatch();
      return updatedCount;
    }
  }

  private int addUpdateWhenEncodingChanges(ResultSet row, PreparedStatement update)
      throws SQLException {
    var encoded = TitleSortUtil.computeTitleSort(row.getString("title"));
    if (encoded.equals(row.getString("title_sort"))) {
      return 0;
    }

    update.setString(1, encoded);
    update.setObject(2, row.getObject("id"));
    update.addBatch();
    return 1;
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
