package db.migration;

import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.utils.TitleSortUtil;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import lombok.experimental.UtilityClass;

@UtilityClass
class StoredTitleSorts {

  private static final int BATCH_SIZE = 1000;

  /** Rewrites every stored sort title whose encoding differs from the current one. */
  static int encodeAsSystemActor(Connection connection) throws SQLException {
    nameSystemActor(connection);
    return encodeStoredTitleSorts(connection);
  }

  private static void nameSystemActor(Connection connection) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT set_config('streamarr.actor_id', ?, true)")) {
      statement.setString(1, SystemActor.ID.toString());
      statement.execute();
    }
  }

  private static int encodeStoredTitleSorts(Connection connection) throws SQLException {
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

  private static int addUpdateWhenEncodingChanges(ResultSet row, PreparedStatement update)
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
}
