package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.WORKER_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.StreamingRigFixture.StreamingRig;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds segment requests against a real worker session server while a loopback worker uploads
 * segments and ends job attempts. The stall threshold lies far beyond every bound in these tests,
 * so a held request that misses its wake never ends its wait in time and fails the test.
 */
@Tag("IntegrationTest")
@DisplayName("Held Segment Request Concurrency Integration Tests")
class HeldSegmentRequestConcurrencyIT {

  private static final Path SOURCE_ROOT = Path.of("/media");
  private static final Duration UNREACHED_STALL_THRESHOLD = Duration.ofMinutes(10);
  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final String SEGMENT_NAME = "segment0.m4s";
  private static final int HELD_REQUEST_COUNT = 16;

  @TempDir Path tempDir;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private PausingSegmentStore segmentStore;
  private WorkerSessionServer server;
  private LoopbackVariantWorker worker;
  private LookingTranscodeExecutor transcodeExecutor;
  private StreamingRig rig;
  private FakeRuntimeStreamSessionRegistry registry;
  private final List<UUID> startedSessionIds = new ArrayList<>();
  private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();

  @BeforeEach
  void setUp() throws Exception {
    segmentStore = new PausingSegmentStore(new LocalSegmentStore(tempDir.resolve("segments")));
    server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(segmentStore)
            .meterRegistry(meterRegistry)
            .build();
    server.start();
    worker = LoopbackVariantWorker.register(server.port());
    transcodeExecutor =
        new LookingTranscodeExecutor(
            new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, SOURCE_ROOT));
    registry = new FakeRuntimeStreamSessionRegistry();
    rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(
                StreamingProperties.builder()
                    .targetSegmentDuration(Duration.ofSeconds(6))
                    .producerStallThreshold(UNREACHED_STALL_THRESHOLD)
                    .build())
            .runtimeRegistry(registry)
            .heldRequests(server.heldSegmentRequests())
            .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (segmentStore != null) {
      segmentStore.releasePublication();
    }

    // Destroying each session wakes any request still held, so no request thread outlives the test.
    startedSessionIds.forEach(this::destroy);
    requests.close();

    if (worker != null) {
      worker.close();
    }

    if (server != null) {
      server.close();
    }
  }

  @Test
  @DisplayName("Should answer every request held on one segment when the worker uploads it")
  void shouldAnswerEveryRequestHeldOnOneSegmentWhenTheWorkerUploadsIt() throws Exception {
    var session = startedSession();
    var job = worker.nextJob();
    var uploaded = segmentBytes("every held request");

    var deliveries = new ArrayList<Future<SegmentDelivery>>();
    for (var request = 0; request < HELD_REQUEST_COUNT; request++) {
      deliveries.add(deliverAsync(session));
    }

    // Every request has looked at its running producer and found no segment.
    transcodeExecutor.awaitLooks(HELD_REQUEST_COUNT);

    worker.upload(job, SEGMENT_NAME, uploaded).get(BOUND.toSeconds(), TimeUnit.SECONDS);

    for (var delivery : deliveries) {
      assertThat(delivery.get(BOUND.toSeconds(), TimeUnit.SECONDS))
          .isEqualTo(new SegmentDelivery.Ready(uploaded));
    }
  }

  @Test
  @DisplayName("Should hold no request when destroying the session ends every held request")
  void shouldHoldNoRequestWhenDestroyingTheSessionEndsEveryHeldRequest() throws Exception {
    var session = startedSession();
    worker.nextJob();

    var deliveries = new ArrayList<Future<SegmentDelivery>>();
    for (var request = 0; request < HELD_REQUEST_COUNT; request++) {
      deliveries.add(deliverAsync(session));
    }

    transcodeExecutor.awaitLooks(HELD_REQUEST_COUNT);

    destroy(session.getSessionId());

    for (var delivery : deliveries) {
      assertThat(delivery.get(BOUND.toSeconds(), TimeUnit.SECONDS))
          .isEqualTo(new SegmentDelivery.SessionEnded());
    }

    assertThat(meterRegistry.get("streamarr.streaming.held_segment_requests").gauge().value())
        .isZero();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(RaceOrder.class)
  @DisplayName(
      "Should answer a held request when its segment's upload races the death of its producer")
  void shouldAnswerHeldRequestWhenItsSegmentsUploadRacesTheDeathOfItsProducer(RaceOrder order)
      throws Exception {
    var session = startedSession();
    var job = worker.nextJob();
    var dyingAttempt = segmentBytes("the dying attempt");
    var dyingUpload = new AtomicReference<CompletableFuture<UploadSegmentResponse>>();
    // Both events land after the request found its producer running and its segment missing, and
    // before it waits: the request must still wake, then see the segment or the death.
    transcodeExecutor.afterNextLook(
        () -> {
          if (order == RaceOrder.UPLOAD_BEFORE_DEATH) {
            dyingUpload.set(worker.uploadAndAwait(job, SEGMENT_NAME, dyingAttempt));
          }

          endJobAttempt(session, job);
          if (order == RaceOrder.DEATH_BEFORE_UPLOAD) {
            dyingUpload.set(worker.uploadAndAwait(job, SEGMENT_NAME, dyingAttempt));
          }
        });

    var delivery = deliverAsync(session);

    switch (order) {
      case UPLOAD_BEFORE_DEATH -> {
        assertThat(delivery.get(BOUND.toSeconds(), TimeUnit.SECONDS))
            .isEqualTo(new SegmentDelivery.Ready(dyingAttempt));
        assertThat(dyingUpload.get()).isCompleted();
      }
      case DEATH_BEFORE_UPLOAD -> {
        var replacementAttempt = segmentBytes("the replacement attempt");
        var replacement = worker.nextJob();
        worker
            .upload(replacement, SEGMENT_NAME, replacementAttempt)
            .get(BOUND.toSeconds(), TimeUnit.SECONDS);

        assertThat(delivery.get(BOUND.toSeconds(), TimeUnit.SECONDS))
            .isEqualTo(new SegmentDelivery.Ready(replacementAttempt));
        assertThat(replacement.getJobAttemptId()).isNotEqualTo(job.getJobAttemptId());
        assertUploadRefused(dyingUpload.get());
      }
    }
  }

  @Test
  @DisplayName(
      "Should answer a request that arrives while its segment is stored but not yet readable")
  void shouldAnswerRequestThatArrivesWhileItsSegmentIsStoredButNotYetReadable() throws Exception {
    var session = startedSession();
    var job = worker.nextJob();
    var uploaded = segmentBytes("a paused publication");
    segmentStore.pauseNextPublication(PublicationPause.BEFORE_THE_SEGMENT_IS_READABLE);
    var upload = worker.upload(job, SEGMENT_NAME, uploaded);
    segmentStore.awaitPausedPublication();

    var delivery = deliverAsync(session);
    // The request found no segment and has begun to look at its producer.
    transcodeExecutor.awaitLooks(1);
    segmentStore.releasePublication();

    upload.get(BOUND.toSeconds(), TimeUnit.SECONDS);
    assertThat(delivery.get(BOUND.toSeconds(), TimeUnit.SECONDS))
        .isEqualTo(new SegmentDelivery.Ready(uploaded));
  }

  @Test
  @DisplayName(
      "Should answer a request that arrives after its segment is readable but before the upload wakes held requests")
  void shouldAnswerRequestThatArrivesAfterItsSegmentIsReadableButBeforeTheUploadWakesHeldRequests()
      throws Exception {
    var session = startedSession();
    var job = worker.nextJob();
    var uploaded = segmentBytes("a readable segment");
    segmentStore.pauseNextPublication(PublicationPause.AFTER_THE_SEGMENT_IS_READABLE);
    var upload = worker.upload(job, SEGMENT_NAME, uploaded);
    segmentStore.awaitPausedPublication();

    var delivery = deliverAsync(session);

    assertThat(delivery.get(BOUND.toSeconds(), TimeUnit.SECONDS))
        .isEqualTo(new SegmentDelivery.Ready(uploaded));
    assertThat(transcodeExecutor.looks()).isZero();
    segmentStore.releasePublication();
    upload.get(BOUND.toSeconds(), TimeUnit.SECONDS);
  }

  private enum RaceOrder {
    UPLOAD_BEFORE_DEATH,
    DEATH_BEFORE_UPLOAD
  }

  private StreamSession startedSession() {
    var session = defaultSessionBuilder().sourcePath(SOURCE_ROOT.resolve("movie.mkv")).build();
    registry.save(session);
    startedSessionIds.add(session.getSessionId());
    rig.lifecycle().startFirstListedVariant(session);
    return session;
  }

  private Future<SegmentDelivery> deliverAsync(StreamSession session) {
    return requests.submit(
        () ->
            rig.coordinator()
                .deliver(session.getSessionId(), StreamSession.defaultVariant(), SEGMENT_NAME));
  }

  // Removes the session and forgets it, as destroying the session does.
  private void destroy(UUID sessionId) {
    registry.removeById(sessionId);
    rig.coordinator().forgetSession(sessionId);
  }

  // Reports the job attempt failed, then waits until the server has released it: the release
  // fires the wake that the variant's held requests share.
  private void endJobAttempt(StreamSession session, VariantJob job) {
    var released =
        server
            .heldSegmentRequests()
            .nextWake(session.getSessionId(), StreamSession.defaultVariant());
    worker.reportFailed(job);
    try {
      assertThat(released.await(BOUND)).as("the server released the job attempt").isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while the server released the job attempt", e);
    }
  }

  private static byte[] segmentBytes(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }

  private static void assertUploadRefused(CompletableFuture<UploadSegmentResponse> upload) {
    assertThatThrownBy(() -> upload.get(BOUND.toSeconds(), TimeUnit.SECONDS))
        .rootCause()
        .matches(
            failure -> Status.fromThrowable(failure).getCode() == Status.Code.PERMISSION_DENIED);
  }

  private enum PublicationPause {
    BEFORE_THE_SEGMENT_IS_READABLE,
    AFTER_THE_SEGMENT_IS_READABLE
  }

  /** A local segment store that can hold one publication open at a chosen point. */
  private static final class PausingSegmentStore implements SegmentStore {

    private final LocalSegmentStore delegate;
    private final CountDownLatch paused = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicReference<PublicationPause> nextPause = new AtomicReference<>();

    private PausingSegmentStore(LocalSegmentStore delegate) {
      this.delegate = delegate;
    }

    private void pauseNextPublication(PublicationPause pause) {
      nextPause.set(pause);
    }

    private void awaitPausedPublication() throws InterruptedException {
      assertThat(paused.await(BOUND.toSeconds(), TimeUnit.SECONDS))
          .as("the upload reached its publication")
          .isTrue();
    }

    private void releasePublication() {
      released.countDown();
    }

    @Override
    public byte[] readSegment(UUID sessionId, String segmentName) {
      return delegate.readSegment(sessionId, segmentName);
    }

    @Override
    public boolean segmentExists(UUID sessionId, String segmentName) {
      return delegate.segmentExists(sessionId, segmentName);
    }

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
      var prepared = delegate.prepareSegment(sessionId, segmentName, data);
      var pause = nextPause.getAndSet(null);
      return new PreparedSegment() {
        @Override
        public SegmentPublication publish() {
          holdAt(pause, PublicationPause.BEFORE_THE_SEGMENT_IS_READABLE);
          var publication = prepared.publish();
          holdAt(pause, PublicationPause.AFTER_THE_SEGMENT_IS_READABLE);
          return publication;
        }

        @Override
        public void close() {
          prepared.close();
        }
      };
    }

    private void holdAt(PublicationPause pause, PublicationPause point) {
      if (pause != point) {
        return;
      }

      paused.countDown();
      try {
        if (!released.await(BOUND.toSeconds(), TimeUnit.SECONDS)) {
          throw new AssertionError("Timed out holding the segment publication");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while holding the segment publication", e);
      }
    }

    @Override
    public void deleteSession(UUID sessionId) {
      delegate.deleteSession(sessionId);
    }
  }

  /**
   * The remote executor, counting each look that recovery takes at a producer's liveness. A look
   * counts before the executor answers, and an armed action runs after it answers.
   */
  private static final class LookingTranscodeExecutor implements TranscodeExecutor {

    private final TranscodeExecutor delegate;
    private final Semaphore looks = new Semaphore(0);
    private final AtomicReference<Runnable> afterNextLook = new AtomicReference<>();

    private LookingTranscodeExecutor(TranscodeExecutor delegate) {
      this.delegate = delegate;
    }

    private void afterNextLook(Runnable action) {
      afterNextLook.set(action);
    }

    private void awaitLooks(int count) throws InterruptedException {
      assertThat(looks.tryAcquire(count, BOUND.toSeconds(), TimeUnit.SECONDS))
          .as("%d held requests looked at the producer", count)
          .isTrue();
    }

    private int looks() {
      return looks.availablePermits();
    }

    @Override
    public boolean isRunning(UUID sessionId, String variantLabel) {
      looks.release();
      var running = delegate.isRunning(sessionId, variantLabel);
      var action = afterNextLook.getAndSet(null);
      if (action != null) {
        action.run();
      }

      return running;
    }

    @Override
    public Optional<ExecutionTargetId> runningExecutionTarget(UUID sessionId, String variantLabel) {
      return delegate.runningExecutionTarget(sessionId, variantLabel);
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      return delegate.start(request);
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target) {
      return delegate.start(request, target);
    }

    @Override
    public void stop(UUID sessionId) {
      delegate.stop(sessionId);
    }

    @Override
    public void destroySession(UUID sessionId) {
      delegate.destroySession(sessionId);
    }

    @Override
    public void stopVariant(UUID sessionId, String variantLabel) {
      delegate.stopVariant(sessionId, variantLabel);
    }

    @Override
    public Optional<TranscodeFailure> terminalFailure(UUID sessionId) {
      return delegate.terminalFailure(sessionId);
    }

    @Override
    public boolean isHealthy() {
      return delegate.isHealthy();
    }

    @Override
    public int availableSlots() {
      return delegate.availableSlots();
    }

    @Override
    public int availableSlots(TranscodeDecision decision) {
      return delegate.availableSlots(decision);
    }

    @Override
    public Set<ExecutionTargetId> executionTargets(TranscodeDecision decision) {
      return delegate.executionTargets(decision);
    }

    @Override
    public Set<ExecutionTargetId> executionTargets(WorkerVerifiedEncoder pinnedEncoder) {
      return delegate.executionTargets(pinnedEncoder);
    }

    @Override
    public Set<String> verifiedVideoCodecFamilies() {
      return delegate.verifiedVideoCodecFamilies();
    }

    @Override
    public Set<String> encodableVideoCodecFamilies() {
      return delegate.encodableVideoCodecFamilies();
    }
  }

  /** A worker session that the test drives: it receives variant jobs and uploads segments. */
  private static final class LoopbackVariantWorker implements AutoCloseable {

    private final ManagedChannel channel;
    private final WorkerIdentity identity;
    private final StreamObserver<EstablishWorkerSessionRequest> requests;
    private final BlockingQueue<EstablishWorkerSessionResponse> responses =
        new LinkedBlockingQueue<>();
    private WorkerSessionAccepted session;

    private LoopbackVariantWorker(int port) {
      channel = plaintextChannelBuilder(port, WORKER_ID).build();
      identity =
          WorkerIdentity.newBuilder()
              .setWorkerId(toProto(WORKER_ID))
              .setBootId(toProto(UUID.randomUUID()))
              .build();
      requests =
          TranscodeWorkerServiceGrpc.newStub(channel)
              .establishWorkerSession(
                  new StreamObserver<>() {
                    @Override
                    public void onNext(EstablishWorkerSessionResponse value) {
                      responses.add(value);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                      // The test observes the session only through the commands it receives.
                    }

                    @Override
                    public void onCompleted() {
                      // The server ends the session when the test closes the channel.
                    }
                  });
    }

    private static LoopbackVariantWorker register(int port) throws InterruptedException {
      var worker = new LoopbackVariantWorker(port);
      worker.requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setRegistration(
                  WorkerRegistration.newBuilder()
                      .setWorker(worker.identity)
                      .setCapabilities(
                          WorkerCapabilities.newBuilder()
                              .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID)))
                      .setAvailableSlots(4))
              .build());
      worker.session = worker.nextResponse().getSessionAccepted();
      return worker;
    }

    private EstablishWorkerSessionResponse nextResponse() throws InterruptedException {
      var response = responses.poll(BOUND.toSeconds(), TimeUnit.SECONDS);
      assertThat(response).as("worker session response must arrive").isNotNull();
      return response;
    }

    /** The next job the server starts on this worker, skipping stop commands. */
    private VariantJob nextJob() throws InterruptedException {
      while (true) {
        var response = nextResponse();
        if (response.hasStartVariant()) {
          return response.getStartVariant().getJob();
        }
      }
    }

    private CompletableFuture<UploadSegmentResponse> upload(
        VariantJob job, String segmentName, byte[] data) {
      var response = new CompletableFuture<UploadSegmentResponse>();
      var upload =
          TranscodeWorkerServiceGrpc.newStub(channel)
              .uploadSegment(
                  new StreamObserver<>() {
                    @Override
                    public void onNext(UploadSegmentResponse value) {
                      response.complete(value);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                      response.completeExceptionally(throwable);
                    }

                    @Override
                    public void onCompleted() {
                      response.completeExceptionally(
                          new IllegalStateException("Segment upload closed without a response"));
                    }
                  });
      upload.onNext(
          UploadSegmentRequest.newBuilder().setMetadata(metadata(job, segmentName, data)).build());
      upload.onNext(UploadSegmentRequest.newBuilder().setData(ByteString.copyFrom(data)).build());
      upload.onCompleted();
      return response;
    }

    // Uploads and waits for the server's answer, accepted or refused.
    private CompletableFuture<UploadSegmentResponse> uploadAndAwait(
        VariantJob job, String segmentName, byte[] data) {
      var response = upload(job, segmentName, data);
      try {
        response.handle((_, _) -> null).get(BOUND.toSeconds(), TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new AssertionError("The server did not answer the segment upload", e);
      }

      return response;
    }

    private SegmentUploadMetadata metadata(VariantJob job, String segmentName, byte[] data) {
      return SegmentUploadMetadata.newBuilder()
          .setWorkerSessionId(session.getWorkerSessionId())
          .setWorker(identity)
          .setStreamSessionId(job.getStreamSessionId())
          .setJobId(job.getJobId())
          .setJobAttemptId(job.getJobAttemptId())
          .setVariantLabel(job.getVariant().getVariantLabel())
          .setSegmentName(segmentName)
          .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4)
          .setContentLengthBytes(data.length)
          .build();
    }

    private void reportFailed(VariantJob job) {
      requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setJobAttemptFailed(
                  JobAttemptFailed.newBuilder()
                      .setJobAttemptId(job.getJobAttemptId())
                      .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED))
              .build());
    }

    @Override
    public void close() throws InterruptedException {
      requests.onCompleted();
      channel.shutdownNow().awaitTermination(BOUND.toSeconds(), TimeUnit.SECONDS);
    }
  }
}
