package com.streamarr.server.fakes;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.remuxDecision;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.VideoFormatConstraint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FakeCreatingStreamingService implements StreamingService {
  private final FakeStreamingService streams;
  private Runnable beforeNextDestroy = () -> {};
  private Runnable afterNextSnapshot = () -> {};
  private final Set<UUID> failingDestructions = ConcurrentHashMap.newKeySet();

  public FakeCreatingStreamingService() {
    this(new FakeRuntimeStreamSessionRegistry());
  }

  public FakeCreatingStreamingService(FakeRuntimeStreamSessionRegistry registry) {
    streams = new FakeStreamingService(registry);
  }

  public void beforeNextDestroy(Runnable callback) {
    beforeNextDestroy = callback;
  }

  public void afterNextSnapshot(Runnable callback) {
    afterNextSnapshot = callback;
  }

  public void failNextDestroy(UUID sessionId) {
    failingDestructions.add(sessionId);
  }

  public void addSession(StreamSession session) {
    streams.addSession(session);
  }

  @Override
  public Outcome<StreamSession, CreateStreamSessionRejection> createSession(
      CreateStreamSessionCommand command) {
    var session =
        defaultSessionBuilder()
            .sessionId(command.streamSessionId())
            .mediaFileId(command.mediaFileId())
            .authority(command.identity().playbackAuthority())
            .options(command.options())
            .transcodeDecision(
                command.videoFormatConstraint() == VideoFormatConstraint.ENCODE_ONLY
                    ? fullTranscodeDecision("h264")
                    : remuxDecision())
            .build();
    streams.addSession(session);
    return Outcome.accepted(session);
  }

  @Override
  public Optional<StreamSession> accessSession(PlaybackRequest request) {
    return streams.accessSession(request);
  }

  @Override
  public void destroySession(UUID sessionId) {
    var callback = beforeNextDestroy;
    beforeNextDestroy = () -> {};
    callback.run();
    if (failingDestructions.remove(sessionId)) {
      throw new IllegalStateException("Child cleanup failed");
    }

    streams.destroySession(sessionId);
  }

  @Override
  public void destroySession(UUID sessionId, UUID profileId) {
    destroySession(sessionId);
  }

  @Override
  public Collection<StreamSession> getAllSessions() {
    var snapshot = List.copyOf(streams.getAllSessions());
    var callback = afterNextSnapshot;
    afterNextSnapshot = () -> {};
    callback.run();
    return snapshot;
  }

  @Override
  public int getActiveSessionCount() {
    return streams.getActiveSessionCount();
  }
}
