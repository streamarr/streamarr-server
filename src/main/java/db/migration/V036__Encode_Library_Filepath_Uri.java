package db.migration;

import com.streamarr.server.services.library.FilepathCodec;
import java.nio.file.Path;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V036__Encode_Library_Filepath_Uri extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    try (var select = context.getConnection().createStatement();
        var update =
            context
                .getConnection()
                .prepareStatement("UPDATE library SET filepath_uri = ? WHERE id = ?::uuid")) {

      var rows =
          select.executeQuery(
              "SELECT id, filepath_uri FROM library WHERE filepath_uri NOT LIKE 'file://%'");

      while (rows.next()) {
        var encoded = FilepathCodec.encode(Path.of(rows.getString("filepath_uri")));

        update.setString(1, encoded);
        update.setString(2, rows.getString("id"));
        update.addBatch();
      }

      update.executeBatch();
    }
  }
}
