package db.migration;

import com.streamarr.server.services.filepath.FilepathCodec;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

@Slf4j
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
      var updatedCount = 0;
      var skippedCount = 0;

      while (rows.next()) {
        var id = rows.getString("id");
        var filepathUri = rows.getString("filepath_uri");
        String filename;
        try {
          filename = FilepathCodec.filenameOf(filepathUri);
        } catch (IllegalArgumentException exception) {
          skippedCount++;
          log.warn(
              "Skipping filename migration for media file id={} filepathUri={}: {}",
              id,
              filepathUri,
              exception.getMessage());
          continue;
        }
        if (!filename.equals(rows.getString("filename"))) {
          update.setString(1, filename);
          update.setString(2, id);
          update.addBatch();
          updatedCount++;
        }
      }

      update.executeBatch();
      log.info(
          "Media file filename migration completed: updated={}, skipped={}",
          updatedCount,
          skippedCount);
    }
  }
}
