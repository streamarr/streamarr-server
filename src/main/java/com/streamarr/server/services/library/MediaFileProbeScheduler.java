package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.ProbeSchedulingException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.events.library.MediaFileProbeRequested;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeRequests;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Builder
@RequiredArgsConstructor
public class MediaFileProbeScheduler {

  private final MediaFileRepository mediaFileRepository;
  private final PersistedProbeReader reader;
  private final ProbeRequests probeRequests;
  private final FileSystem fileSystem;

  @EventListener
  public void onProbeRequested(MediaFileProbeRequested event) {
    var mediaFile =
        mediaFileRepository
            .findById(event.mediaFileId())
            .orElseThrow(() -> new MediaFileNotFoundException(event.mediaFileId()));
    var observedSnapshot = snapshot(mediaFile);
    if (observedSnapshot.isEmpty()) {
      return;
    }

    var sourceSnapshot = observedSnapshot.get();

    if (reader
        .find(mediaFile.getId())
        .filter(outcome -> outcome.matches(sourceSnapshot, ProbeVersion.CURRENT))
        .isPresent()) {
      return;
    }

    probeRequests.request(
        ProbeRequest.builder()
            .mediaFileId(mediaFile.getId())
            .libraryId(mediaFile.getLibraryId())
            .filepathUri(mediaFile.getFilepathUri())
            .snapshot(sourceSnapshot)
            .probeVersion(ProbeVersion.CURRENT)
            .build());
  }

  private Optional<SourceFileSnapshot> snapshot(MediaFile mediaFile) {
    var path = FilepathCodec.decode(fileSystem, mediaFile.getFilepathUri());
    try {
      var attributes = Files.readAttributes(path, BasicFileAttributes.class);
      return Optional.of(
          new SourceFileSnapshot(attributes.size(), attributes.lastModifiedTime().toInstant()));
    } catch (NoSuchFileException _) {
      return Optional.empty();
    } catch (IOException exception) {
      throw new ProbeSchedulingException(mediaFile.getId(), exception);
    }
  }
}
