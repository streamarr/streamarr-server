package db.migration;

import com.streamarr.server.services.library.FilepathCodec;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V036__Encode_Library_Filepath_Uri extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    try (Statement select = context.getConnection().createStatement()) {
      ResultSet rows =
          select.executeQuery(
              "SELECT id, filepath_uri FROM library WHERE filepath_uri NOT LIKE 'file://%'");

      while (rows.next()) {
        var id = rows.getString("id");
        var plainPath = rows.getString("filepath_uri");
        var encoded = FilepathCodec.encode(Path.of(plainPath));

        try (var update =
            context
                .getConnection()
                .prepareStatement("UPDATE library SET filepath_uri = ? WHERE id = ?::uuid")) {
          update.setString(1, encoded);
          update.setString(2, id);
          update.executeUpdate();
        }
      }
    }
  }
}
