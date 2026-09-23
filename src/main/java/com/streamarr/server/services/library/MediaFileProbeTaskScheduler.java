package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.exceptions.MediaFileNotFoundException;
import com.streamarr.server.exceptions.ProbeTaskSchedulingException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.services.events.library.MediaFileProbeTaskRequested;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Builder
@RequiredArgsConstructor
public class MediaFileProbeTaskScheduler {

  private final MediaFileRepository mediaFileRepository;
  private final PersistedProbeReader reader;
  private final ProbeTaskRequests probeTaskRequests;
  private final FileSystem fileSystem;

  @EventListener
  public void onProbeTaskRequested(MediaFileProbeTaskRequested event) {
    schedule(event.mediaFileId());
  }

  /**
   * Requests a probe of the media file unless its stored outcome already matches the source.
   * Returns the inputs whose outcome the file needs, or nothing when its source no longer exists.
   */
  public Optional<ProbeInputs> schedule(UUID mediaFileId) {
    var mediaFile =
        mediaFileRepository
            .findById(mediaFileId)
            .orElseThrow(() -> new MediaFileNotFoundException(mediaFileId));
    var observedSnapshot = snapshot(mediaFile);
    if (observedSnapshot.isEmpty()) {
      return Optional.empty();
    }

    var inputs = new ProbeInputs(observedSnapshot.get(), ProbeVersion.CURRENT);
    if (reader
        .find(mediaFile.getId())
        .filter(outcome -> outcome.matches(inputs.snapshot(), inputs.probeVersion()))
        .isPresent()) {
      return Optional.of(inputs);
    }

    probeTaskRequests.request(
        ProbeTaskRequest.builder()
            .mediaFileId(mediaFile.getId())
            .libraryId(mediaFile.getLibraryId())
            .filepathUri(mediaFile.getFilepathUri())
            .snapshot(inputs.snapshot())
            .probeVersion(inputs.probeVersion())
            .build());
    return Optional.of(inputs);
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
      throw new ProbeTaskSchedulingException(mediaFile.getId(), exception);
    }
  }
}
