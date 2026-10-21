package com.streamarr.server.services.library;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
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
  private final KeyframePacketScanRequests packetScans;
  private final FileSystem fileSystem;

  /** Playback waits for this probe, so the probe runs before every queued probe. */
  @EventListener
  public void onProbeTaskRequested(MediaFileProbeTaskRequested event) {
    schedule(event.mediaFileId(), probeTaskRequests::requestAheadOfQueuedProbes);
  }

  /**
   * Requests a probe of the media file unless its stored outcome already matches the source, and
   * retries a failed attempt at once. A matching outcome that still needs a keyframe packet scan
   * gets one, so a scan that ended while the source was missing runs again. Returns the inputs
   * whose outcome the file needs, or nothing when its source no longer exists.
   */
  public Optional<ProbeInputs> schedule(UUID mediaFileId) {
    return schedule(mediaFileId, probeTaskRequests::requestRetryingFailure);
  }

  /** Schedules like {@link #schedule(UUID)} with a snapshot the caller already observed. */
  public ProbeInputs schedule(UUID mediaFileId, SourceFileSnapshot observed) {
    return request(mediaFile(mediaFileId), observed, probeTaskRequests::requestRetryingFailure);
  }

  private Optional<ProbeInputs> schedule(UUID mediaFileId, Consumer<ProbeTaskRequest> requests) {
    var mediaFile = mediaFile(mediaFileId);
    return snapshot(mediaFile).map(observed -> request(mediaFile, observed, requests));
  }

  private ProbeInputs request(
      MediaFile mediaFile, SourceFileSnapshot observed, Consumer<ProbeTaskRequest> requests) {
    var inputs = new ProbeInputs(observed, ProbeVersion.CURRENT);
    var stored =
        reader
            .find(mediaFile.getId())
            .filter(outcome -> outcome.matches(inputs.snapshot(), inputs.probeVersion()));
    if (stored.isPresent()) {
      requestPacketScanIfNeeded(mediaFile, inputs, stored.get().outcome());
      return inputs;
    }

    requests.accept(
        ProbeTaskRequest.builder()
            .mediaFileId(mediaFile.getId())
            .libraryId(mediaFile.getLibraryId())
            .filepathUri(mediaFile.getFilepathUri())
            .snapshot(inputs.snapshot())
            .probeVersion(inputs.probeVersion())
            .build());
    return inputs;
  }

  // Scheduling is idempotent per media file, so a pending or running scan is left alone.
  private void requestPacketScanIfNeeded(
      MediaFile mediaFile, ProbeInputs inputs, ProbeOutcome outcome) {
    if (!outcome.needsKeyframePacketScan()) {
      return;
    }

    packetScans.request(
        KeyframePacketScanTaskRequest.builder()
            .mediaFileId(mediaFile.getId())
            .filepathUri(mediaFile.getFilepathUri())
            .snapshot(inputs.snapshot())
            .probeVersion(inputs.probeVersion())
            .build());
  }

  private MediaFile mediaFile(UUID mediaFileId) {
    return mediaFileRepository
        .findById(mediaFileId)
        .orElseThrow(() -> new MediaFileNotFoundException(mediaFileId));
  }

  private Optional<SourceFileSnapshot> snapshot(MediaFile mediaFile) {
    var path = FilepathCodec.decode(fileSystem, mediaFile.getFilepathUri());
    try {
      return SourceFileSnapshots.read(path);
    } catch (IOException exception) {
      throw new ProbeTaskSchedulingException(mediaFile.getId(), exception);
    }
  }
}
