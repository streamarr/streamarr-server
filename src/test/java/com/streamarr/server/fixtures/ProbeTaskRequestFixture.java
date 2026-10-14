package com.streamarr.server.fixtures;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.services.filepath.FilepathCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;

public final class ProbeTaskRequestFixture {

  private ProbeTaskRequestFixture() {}

  /** The current-version request that a scan of the media file's source would make now. */
  public static ProbeTaskRequest requestFor(MediaFile file) throws IOException {
    return ProbeTaskRequest.builder()
        .mediaFileId(file.getId())
        .libraryId(file.getLibraryId())
        .filepathUri(file.getFilepathUri())
        .snapshot(snapshotOf(file))
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }

  public static SourceFileSnapshot snapshotOf(MediaFile file) throws IOException {
    return SourceFileSnapshot.of(
        Files.readAttributes(
            FilepathCodec.decode(file.getFilepathUri()), BasicFileAttributes.class));
  }
}
