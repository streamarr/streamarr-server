package com.streamarr.server.controllers;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeStreamingService;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StoredSegment;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.support.AuthTestSupport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Runs the server on a real port with one stored segment of {@link #SEGMENT_LENGTH} bytes in a
 * local segment store, so a test sees the exact bytes that reach the client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractStoredSegmentIntegrationTest extends AbstractIntegrationTest {

  // Longer than the socket buffers, so one answer needs several transfers.
  static final int SEGMENT_LENGTH = 3 * 1024 * 1024;
  static final SessionDeletingStore STORE =
      new SessionDeletingStore(new LocalSegmentStore(segmentFolder()));
  private static final FakeStreamingService STUB_SERVICE = new FakeStreamingService();

  @LocalServerPort int port;
  @Autowired AuthTestSupport authTestSupport;

  @TestBean StreamingService streamingService;
  @TestBean SegmentStore segmentStore;
  @TestBean TranscodeExecutor transcodeExecutor;

  StreamSession session;
  byte[] segmentBytes;
  private AuthTestSupport.TestIdentity identity;

  static StreamingService streamingService() {
    return STUB_SERVICE;
  }

  static SegmentStore segmentStore() {
    return STORE;
  }

  static TranscodeExecutor transcodeExecutor() {
    return new FakeTranscodeExecutor();
  }

  private static Path segmentFolder() {
    try {
      return Files.createTempDirectory("stored-segment-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @BeforeEach
  void storeSegment() {
    identity = authTestSupport.createIdentity();
    session = StreamSessionFixture.sessionWithDurationBuilder(7200).build();
    session.setHandle(StreamSessionFixture.mintHandle(1L, TranscodeStatus.ACTIVE));
    STUB_SERVICE.addSession(session);
    segmentBytes = new byte[SEGMENT_LENGTH];
    for (var index = 0; index < SEGMENT_LENGTH; index++) {
      segmentBytes[index] = (byte) (index % 251);
    }

    STORE.storeSegment(session.getSessionId(), "segment0.m4s", segmentBytes);
  }

  @AfterEach
  void deleteStoredSession() {
    STORE.deleteSession(session.getSessionId());
    authTestSupport.deleteIdentity(identity);
  }

  @AfterAll
  static void deleteStoredSessions() {
    STORE.shutdown();
  }

  /** The stored segment's path and playback token, relative to the server's root. */
  String segmentPathAndQuery() {
    return "/api/stream/%s/segment0.m4s?t=%s"
        .formatted(
            session.getSessionId(),
            authTestSupport.playbackBearer(identity, session.getSessionId()));
  }

  /** Stores segments as files and can delete a session right after one of its segments opens. */
  static final class SessionDeletingStore implements SegmentStore {

    private final LocalSegmentStore files;
    private final AtomicReference<UUID> deletedAfterOpening = new AtomicReference<>();

    private SessionDeletingStore(LocalSegmentStore files) {
      this.files = files;
    }

    void deleteAfterOpening(UUID sessionId) {
      deletedAfterOpening.set(sessionId);
    }

    private void shutdown() {
      files.shutdown();
    }

    @Override
    public Optional<StoredSegment> openSegment(UUID sessionId, String segmentName) {
      var opened = files.openSegment(sessionId, segmentName);
      if (sessionId.equals(deletedAfterOpening.getAndSet(null))) {
        files.deleteSession(sessionId);
      }

      return opened;
    }

    @Override
    public boolean segmentExists(UUID sessionId, String segmentName) {
      return files.segmentExists(sessionId, segmentName);
    }

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName) {
      return files.prepareSegment(sessionId, segmentName);
    }

    @Override
    public void deleteSession(UUID sessionId) {
      files.deleteSession(sessionId);
    }
  }
}
