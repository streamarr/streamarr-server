package com.streamarr.server.services.streaming;

import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.services.streaming.events.MediaSegmentPublishedEvent;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import org.springframework.context.ApplicationEventPublisher;

final class WorkerStreamingSmokeFixture implements AutoCloseable {
  private final UUID sourceNamespaceId = UUID.randomUUID();
  private final WorkerSessionServer workerSessions;
  private final WorkerContainerFixture worker;
  private final LocalSegmentStore segmentStore;
  private volatile MediaSegmentPublicationListener uploadCredits;

  // The event publisher defaults to one that only captures, so no listener of the rig reacts.
  @Builder
  private WorkerStreamingSmokeFixture(
      Path sourceRoot,
      LocalSegmentStore segmentStore,
      String ffmpegScript,
      ApplicationEventPublisher eventPublisher) {
    this.segmentStore = segmentStore;
    var rigEvents = Optional.ofNullable(eventPublisher).orElseGet(CapturingEventPublisher::new);
    workerSessions =
        WorkerSessionServer.builder()
            .eventPublisher(event -> publish(event, rigEvents))
            .configuration(
                WorkerSessionServerConfiguration.builder().address("127.0.0.1").port(0).build())
            .segmentStore(segmentStore)
            .meterRegistry(new SimpleMeterRegistry())
            .build();
    worker =
        WorkerContainerFixture.builder()
            .workerSessions(workerSessions)
            .sourceNamespaceId(sourceNamespaceId)
            .sourceRoot(sourceRoot)
            .availableSlots(3)
            .ffmpegScript(ffmpegScript)
            .build();
  }

  void start() throws Exception {
    workerSessions.start();
    worker.start();
  }

  // As the application does, credit each media segment that a worker's upload stores to the job
  // attempt that uploaded it. The relocation rule counts only that credited output, so a rig
  // without the credit relocates where the application waits.
  void creditUploadsTo(ProducerLifecycleService lifecycle) {
    uploadCredits = new MediaSegmentPublicationListener(lifecycle);
  }

  private void publish(Object event, ApplicationEventPublisher rigEvents) {
    var credits = uploadCredits;
    if (credits != null && event instanceof MediaSegmentPublishedEvent published) {
      credits.onMediaSegmentPublished(published);
    }

    rigEvents.publishEvent(event);
  }

  WorkerSessionServer workerSessions() {
    return workerSessions;
  }

  UUID sourceNamespaceId() {
    return sourceNamespaceId;
  }

  WorkerContainerFixture worker() {
    return worker;
  }

  void stopStreamSessionsAndClose(Collection<UUID> streamSessionIds) {
    try {
      streamSessionIds.forEach(workerSessions::stopStreamSession);
    } finally {
      close();
    }
  }

  @Override
  public void close() {
    try {
      worker.close();
    } finally {
      closeSessionServerAndSegmentStore();
    }
  }

  private void closeSessionServerAndSegmentStore() {
    try {
      workerSessions.close();
    } finally {
      segmentStore.shutdown();
    }
  }
}
