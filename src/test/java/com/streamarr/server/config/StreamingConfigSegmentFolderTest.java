package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.Library;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.properties.RemoteTranscodeProperties;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.audit.SystemWork;
import com.streamarr.server.services.library.MediaFolders;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Deleted;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.NoOwnedFolder;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftoversOutcome.Refused;
import com.streamarr.server.services.streaming.local.SegmentFolderLock;
import com.streamarr.server.services.streaming.local.SegmentFolderRefusal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Streaming Config Segment Folder Tests")
class StreamingConfigSegmentFolderTest {

  private static final byte[] SEGMENT = "segment".getBytes();
  private static final String STARTS_METRIC = "streamarr.streaming.segment_folder_leftover_deletes";
  private static final String LEFTOVERS_METRIC = "streamarr.streaming.segment_folder_leftovers";

  private final StreamingConfig config = new StreamingConfig();
  private final SegmentFolderLock lock = config.segmentFolderLock();

  @TempDir Path temp;

  @Test
  @DisplayName(
      "Should delete what an earlier server process left and count it when the server starts with"
          + " a segment folder of its own")
  void
      shouldDeleteWhatAnEarlierServerProcessLeftAndCountItWhenTheServerStartsWithASegmentFolderOfItsOwn()
          throws IOException {
    var segmentFolder = temp.resolve("segments");
    var properties =
        StreamingProperties.builder().segmentBasePath(segmentFolder.toString()).build();
    var sessionId = UUID.randomUUID();
    SegmentStore.PreparedSegment unfinishedUpload;
    try (var earlierProcessLock = config.segmentFolderLock()) {
      config.segmentFolderLeftovers(
          properties, mediaFolders(), earlierProcessLock, new SimpleMeterRegistry());
      var storeOfEarlierProcess = config.segmentStore(properties, earlierProcessLock);
      try (var stored = storeOfEarlierProcess.prepareSegment(sessionId, "720p/0.m4s", SEGMENT)) {
        stored.publish();
      }

      // The process ends before it publishes or discards this upload.
      unfinishedUpload = storeOfEarlierProcess.prepareSegment(sessionId, "720p/1.m4s", SEGMENT);
    }

    try (var _ = unfinishedUpload) {
      var meterRegistry = new SimpleMeterRegistry();

      var outcome = config.segmentFolderLeftovers(properties, mediaFolders(), lock, meterRegistry);

      assertThat(segmentFolder.resolve(sessionId.toString())).doesNotExist();
      assertThat(segmentFolder).isDirectoryNotContaining("glob:**/.upload-*");
      assertThat(outcome).isEqualTo(new Deleted(2, 0));
      assertThat(meterRegistry.get(STARTS_METRIC).tag("outcome", "deleted").counter().count())
          .isEqualTo(1);
      assertThat(meterRegistry.get(LEFTOVERS_METRIC).tag("result", "deleted").counter().count())
          .isEqualTo(2);
      assertThat(meterRegistry.get(LEFTOVERS_METRIC).tag("result", "remaining").counter().count())
          .isZero();
    }
  }

  @Test
  @DisplayName("Should count the refusal when the server starts with the media root as its folder")
  void shouldCountTheRefusalWhenTheServerStartsWithTheMediaRootAsItsFolder() {
    var properties = StreamingProperties.builder().segmentBasePath(mediaRoot().toString()).build();
    var meterRegistry = new SimpleMeterRegistry();

    var outcome = config.segmentFolderLeftovers(properties, mediaFolders(), lock, meterRegistry);

    assertThat(outcome).isEqualTo(new Refused(SegmentFolderRefusal.MEDIA_FOLDER));
    assertThat(meterRegistry.get(STARTS_METRIC).tag("outcome", "media_folder").counter().count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should delete nothing when the server starts with the shared segment folder")
  void shouldDeleteNothingWhenTheServerStartsWithTheSharedSegmentFolder() {
    var properties = StreamingProperties.builder().build();
    var meterRegistry = new SimpleMeterRegistry();

    var outcome = config.segmentFolderLeftovers(properties, mediaFolders(), lock, meterRegistry);

    assertThat(outcome).isEqualTo(new NoOwnedFolder());
    assertThat(meterRegistry.get(STARTS_METRIC).tag("outcome", "no_owned_folder").counter().count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should read the media folders as the System actor when the server starts")
  void shouldReadTheMediaFoldersAsTheSystemActorWhenTheServerStarts() {
    var properties =
        StreamingProperties.builder().segmentBasePath(temp.resolve("segments").toString()).build();
    var readers = new ArrayList<Boolean>();
    var libraryRepository =
        new FakeLibraryRepository() {
          @Override
          public List<Library> findAll() {
            readers.add(SystemWork.isRunningAsSystemActor());
            return super.findAll();
          }
        };
    var mediaFolders =
        new MediaFolders(
            FileSystems.getDefault(),
            new RemoteTranscodeProperties(UUID.randomUUID(), mediaRoot().toString()),
            libraryRepository);

    config.segmentFolderLeftovers(properties, mediaFolders, lock, new SimpleMeterRegistry());

    assertThat(readers).containsExactly(true);
  }

  @AfterEach
  void stopTheServer() throws IOException {
    lock.close();
  }

  private Path mediaRoot() {
    return temp.resolve("media");
  }

  private MediaFolders mediaFolders() {
    return new MediaFolders(
        FileSystems.getDefault(),
        new RemoteTranscodeProperties(UUID.randomUUID(), mediaRoot().toString()),
        new FakeLibraryRepository());
  }
}
