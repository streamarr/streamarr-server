package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.common.io.MoreFiles;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.local.SegmentFolderLeftovers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Starts the server on a segment folder of its own that an earlier server process left stream
 * sessions in, as a process that ended without stopping leaves them.
 */
@Tag("IntegrationTest")
@DisplayName("Segment Folder Leftovers Integration Tests")
class SegmentFolderLeftoversIT extends AbstractIntegrationTest {

  private static final byte[] SEGMENT = "segment".getBytes();
  private static final Path TEMPORARY_FOLDER = createTemporaryFolder();
  private static final Path SEGMENT_FOLDER = TEMPORARY_FOLDER.resolve("segments");
  private static final UUID EARLIER_SESSION_ID = UUID.randomUUID();

  @Autowired private LocalSegmentStore segmentStore;
  @Autowired private MockMvc mockMvc;

  @DynamicPropertySource
  static void segmentFolderOfAnEarlierProcess(DynamicPropertyRegistry registry) {
    leaveAStreamSessionAsAnEarlierProcess();
    registry.add("streaming.segment-base-path", SEGMENT_FOLDER::toString);
  }

  @AfterAll
  static void deleteTheTemporaryFolder() throws IOException {
    MoreFiles.deleteRecursively(TEMPORARY_FOLDER);
  }

  @Test
  @DisplayName(
      "Should delete the stream session's directory that an earlier process left when the server"
          + " starts")
  void shouldDeleteTheStreamSessionsDirectoryThatAnEarlierProcessLeftWhenTheServerStarts()
      throws Exception {
    assertThat(SEGMENT_FOLDER.resolve(EARLIER_SESSION_ID.toString())).doesNotExist();
    mockMvc
        .perform(get("/actuator/health/segment-folder"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  @DisplayName("Should keep a stream session that the server created after it started")
  void shouldKeepAStreamSessionThatTheServerCreatedAfterItStarted() {
    var sessionId = UUID.randomUUID();

    try (var stored = segmentStore.prepareSegment(sessionId, "720p/0.m4s", SEGMENT)) {
      stored.publish();
    }

    assertThat(segmentStore.segmentExists(sessionId, "720p/0.m4s")).isTrue();
    assertThat(SEGMENT_FOLDER.resolve(sessionId.toString())).isDirectory();
  }

  private static void leaveAStreamSessionAsAnEarlierProcess() {
    SegmentFolderLeftovers.builder()
        .segmentFolder(SEGMENT_FOLDER)
        .homeFolder(TEMPORARY_FOLDER.resolve("home"))
        .mediaFolders(List::of)
        .build()
        .delete();
    try (var stored =
        new LocalSegmentStore(SEGMENT_FOLDER)
            .prepareSegment(EARLIER_SESSION_ID, "0.m4s", SEGMENT)) {
      stored.publish();
    }

    assertThat(SEGMENT_FOLDER.resolve(EARLIER_SESSION_ID.toString())).isDirectory();
  }

  private static Path createTemporaryFolder() {
    try {
      return Files.createTempDirectory("streamarr-segment-folder-leftovers");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
