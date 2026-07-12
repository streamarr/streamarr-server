package com.streamarr.server.fakes;

import com.streamarr.server.services.streaming.ActiveTranscodeJobInspection;
import com.streamarr.server.services.streaming.PlaybackTranscodeJobService;
import com.streamarr.server.services.streaming.RuntimeTranscodeCleanup;
import com.streamarr.server.services.streaming.StartPlaybackTranscodeJobCommand;
import com.streamarr.transcode.engine.model.RenditionObservation;
import com.streamarr.transcode.engine.model.RenditionState;
import com.streamarr.transcode.engine.model.TranscodeJobObservation;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FakePlaybackTranscodeJobService implements PlaybackTranscodeJobService {

  private final CopyOnWriteArrayList<StartPlaybackTranscodeJobCommand> starts =
      new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<UUID, Long> generations = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, ActiveTranscodeJobInspection> inspections =
      new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<UUID> terminalCleanups = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<UUID> suspensions = new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<UUID, RuntimeTranscodeCleanup> terminalCleanupBySession =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, RuntimeException> terminalCleanupFailuresBySession =
      new ConcurrentHashMap<>();
  private volatile RuntimeTranscodeCleanup terminalCleanup = RuntimeTranscodeCleanup.COMPLETE;
  private volatile RuntimeTranscodeCleanup suspensionCleanup = RuntimeTranscodeCleanup.COMPLETE;
  private volatile RuntimeException startFailure;

  @Override
  public TranscodeJobObservation start(StartPlaybackTranscodeJobCommand command) {
    if (startFailure != null) {
      throw startFailure;
    }
    starts.add(command);
    var generation =
        generations.compute(command.sessionId(), (_, current) -> current == null ? 1 : current + 1);
    var observation =
        TranscodeJobObservation.builder()
            .jobRef(new TranscodeJobRef(command.sessionId(), generation))
            .state(TranscodeJobState.RUNNING)
            .renditions(
                command.renditions().stream()
                    .map(
                        rendition ->
                            new RenditionObservation(rendition.label(), RenditionState.RUNNING))
                    .toList())
            .build();
    inspections.put(
        command.sessionId(),
        new ActiveTranscodeJobInspection.Observed(observation, command.execution().startNumber()));
    return observation;
  }

  public List<StartPlaybackTranscodeJobCommand> startCommands() {
    return List.copyOf(starts);
  }

  public void failStartsWith(RuntimeException failure) {
    startFailure = failure;
  }

  @Override
  public ActiveTranscodeJobInspection inspectActive(UUID sessionId) {
    return inspections.getOrDefault(sessionId, new ActiveTranscodeJobInspection.None());
  }

  @Override
  public RuntimeTranscodeCleanup suspend(UUID sessionId) {
    suspensions.add(sessionId);
    inspections.remove(sessionId);
    return suspensionCleanup;
  }

  @Override
  public RuntimeTranscodeCleanup cleanupTerminal(UUID sessionId) {
    terminalCleanups.add(sessionId);
    var failure = terminalCleanupFailuresBySession.get(sessionId);
    if (failure != null) {
      throw failure;
    }
    var result = terminalCleanupBySession.getOrDefault(sessionId, terminalCleanup);
    if (result == RuntimeTranscodeCleanup.COMPLETE) {
      inspections.remove(sessionId);
    }
    return result;
  }

  public void makeInspectionUnavailable(UUID sessionId) {
    var current = (ActiveTranscodeJobInspection.Observed) inspections.get(sessionId);
    inspections.put(
        sessionId, new ActiveTranscodeJobInspection.Unavailable(current.observation().jobRef()));
  }

  public void observe(UUID sessionId, TranscodeJobState state, int startNumber) {
    var generation =
        generations.compute(sessionId, (_, current) -> current == null ? 1 : current + 1);
    var observation =
        TranscodeJobObservation.builder()
            .jobRef(new TranscodeJobRef(sessionId, generation))
            .state(state)
            .renditions(observedRenditions(state))
            .build();
    inspections.put(sessionId, new ActiveTranscodeJobInspection.Observed(observation, startNumber));
  }

  public List<UUID> suspensionAttempts() {
    return List.copyOf(suspensions);
  }

  public void returnSuspensionCleanup(RuntimeTranscodeCleanup result) {
    suspensionCleanup = result;
  }

  private static List<RenditionObservation> observedRenditions(TranscodeJobState state) {
    if (state == TranscodeJobState.ABSENT) {
      return List.of();
    }
    var renditionState =
        switch (state) {
          case ADMITTING -> RenditionState.STARTING;
          case RUNNING -> RenditionState.RUNNING;
          case COMPLETED -> RenditionState.COMPLETED;
          case FAILED -> RenditionState.FAILED;
          case STOPPED -> RenditionState.STOPPED;
          case ABSENT -> throw new IllegalStateException("Absent has no rendition observation");
        };
    return List.of(new RenditionObservation("default", renditionState));
  }

  public void returnTerminalCleanup(RuntimeTranscodeCleanup result) {
    terminalCleanup = result;
  }

  public void returnTerminalCleanup(UUID sessionId, RuntimeTranscodeCleanup result) {
    terminalCleanupBySession.put(sessionId, result);
    terminalCleanupFailuresBySession.remove(sessionId);
  }

  public void failTerminalCleanup(UUID sessionId, RuntimeException failure) {
    terminalCleanupFailuresBySession.put(sessionId, failure);
    terminalCleanupBySession.remove(sessionId);
  }

  public List<UUID> terminalCleanupAttempts() {
    return List.copyOf(terminalCleanups);
  }

  public void reset() {
    starts.clear();
    generations.clear();
    inspections.clear();
    terminalCleanups.clear();
    suspensions.clear();
    terminalCleanupBySession.clear();
    terminalCleanupFailuresBySession.clear();
    terminalCleanup = RuntimeTranscodeCleanup.COMPLETE;
    suspensionCleanup = RuntimeTranscodeCleanup.COMPLETE;
    startFailure = null;
  }
}
