package db.migration;

import com.streamarr.server.services.filepath.FilepathCodec;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V049__Derive_Media_File_Filename_From_Filepath_Uri extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    try (var select = context.getConnection().createStatement();
        var update =
            context
                .getConnection()
                .prepareStatement(
                    "UPDATE media_file SET filename = ?, last_modified_on = NOW()"
                        + " WHERE id = ?::uuid")) {

      var rows = select.executeQuery("SELECT id, filename, filepath_uri FROM media_file");

      while (rows.next()) {
        var filename = FilepathCodec.filenameOf(rows.getString("filepath_uri"));
        if (filename.equals(rows.getString("filename"))) {
          continue;
        }

        update.setString(1, filename);
        update.setString(2, rows.getString("id"));
        update.addBatch();
      }

      update.executeBatch();
    }
  }
}
