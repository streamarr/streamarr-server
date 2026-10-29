package com.streamarr.server.services.library;

import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.services.filepath.FilepathCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.util.FileSystemUtils;

/**
 * Source files on disk, each in a library of its own, whose rows an upgrade test stores before the
 * server migrates and starts. The caller stores each file's probe result.
 */
record ProbeUpgradeFixture(Path root, List<UpgradeFile> files) implements AutoCloseable {

  record UpgradeFile(UUID mediaFileId, int probeVersion, Path source) {}

  @FunctionalInterface
  interface ResultSeeder {
    void seed(Connection connection, UpgradeFile file, SourceFileSnapshot snapshot)
        throws SQLException;
  }

  static ProbeUpgradeFixture create(List<Integer> storedProbeVersions) throws IOException {
    var root = Files.createTempDirectory("probe-upgrade-");
    var files = new ArrayList<UpgradeFile>();
    for (var version : storedProbeVersions) {
      var library = Files.createDirectory(root.resolve("library-" + files.size()));
      var source = Files.writeString(library.resolve("Movie (2024).mkv"), "media");
      files.add(new UpgradeFile(UUID.randomUUID(), version, source));
    }

    return new ProbeUpgradeFixture(root, List.copyOf(files));
  }

  List<UUID> mediaFileIds() {
    return files.stream().map(UpgradeFile::mediaFileId).toList();
  }

  void seed(DataSource dataSource, ResultSeeder results) {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var actor =
          connection.prepareStatement("SELECT set_config('streamarr.actor_id', ?, true)")) {
        actor.setString(1, SystemActor.ID.toString());
        actor.execute();
      }

      for (var file : files) {
        var snapshot = seedFile(connection, file);
        results.seed(connection, file, snapshot);
      }

      connection.commit();
    } catch (SQLException | IOException e) {
      throw new AssertionError("Could not store the pre-upgrade probe results", e);
    }
  }

  private static SourceFileSnapshot seedFile(Connection connection, UpgradeFile file)
      throws SQLException, IOException {
    var libraryId = UUID.randomUUID();
    try (var insert =
        connection.prepareStatement(
            """
        INSERT INTO library (id, filepath_uri, name, status, backend, type)
        VALUES (?, ?, 'Upgrade library', 'HEALTHY', 'LOCAL', 'MOVIE')
        """)) {
      insert.setObject(1, libraryId);
      insert.setString(2, FilepathCodec.encode(file.source().getParent()));
      insert.executeUpdate();
    }

    var snapshot =
        new SourceFileSnapshot(
            Files.size(file.source()), Files.getLastModifiedTime(file.source()).toInstant());
    try (var insert =
        connection.prepareStatement(
            """
        INSERT INTO media_file (id, library_id, filepath_uri, filename, size, status)
        VALUES (?, ?, ?, ?, ?, 'MATCHED')
        """)) {
      insert.setObject(1, file.mediaFileId());
      insert.setObject(2, libraryId);
      insert.setString(3, FilepathCodec.encode(file.source()));
      insert.setString(4, file.source().getFileName().toString());
      insert.setLong(5, snapshot.size());
      insert.executeUpdate();
    }

    return snapshot;
  }

  @Override
  public void close() throws IOException {
    FileSystemUtils.deleteRecursively(root);
  }
}
