package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThatNoException;

import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegTranscodeEngine;
import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import com.streamarr.transcode.tls.PemTlsIdentity;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Keepalive Integration Tests")
class TranscodeWorkerKeepaliveIT {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @TempDir Path tempDir;

  /**
   * A frozen relay simulates a half-open connection: both sockets stay open but no bytes flow.
   * Without client keepalive the worker would wait on the dead session until the OS abandons the
   * TCP peer; the keepalive ping's missing ACK must surface the disconnection within seconds.
   */
  @Test
  @DisplayName("Should detect a half-open control-plane connection via client keepalive")
  void shouldDetectHalfOpenControlPlaneConnectionViaClientKeepalive() throws Exception {
    var segmentStore = new LocalSegmentStore(tempDir.resolve("segments"));

    try (var server = server(segmentStore)) {
      server.start();
      try (var relay = new FreezableRelay(server.port());
          var worker = worker(tempDir.resolve("media"))) {
        worker.start("localhost", relay.port());

        relay.freeze();
        var disconnection =
            CompletableFuture.runAsync(
                () -> {
                  try {
                    worker.awaitDisconnection();
                  } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                  } catch (RuntimeException _) {
                    // A failed session surfaces as an exception; detection is what matters.
                  }
                });

        assertThatNoException().isThrownBy(() -> disconnection.get(30, TimeUnit.SECONDS));
      }
    }
  }

  private WorkerSessionServer server(LocalSegmentStore segmentStore) throws URISyntaxException {
    var configuration =
        WorkerSessionServerConfiguration.builder()
            .port(0)
            .trustDomain("streamarr.test")
            .tlsIdentity(
                PemTlsIdentity.builder()
                    .certificate(resource("server-cert.pem"))
                    .privateKey(resource("server-key.fixture"))
                    .trustBundle(resource("ca-cert.pem"))
                    .build())
            .build();
    return new WorkerSessionServer(configuration, segmentStore);
  }

  private TranscodeWorker worker(Path mediaRoot) throws URISyntaxException {
    var configuration =
        TranscodeWorkerConfiguration.builder()
            .workerId(WORKER_ID)
            .bootId(UUID.randomUUID())
            .availableSlots(1)
            .tlsIdentity(
                PemTlsIdentity.builder()
                    .certificate(resource("worker-cert.pem"))
                    .privateKey(resource("worker-key.fixture"))
                    .trustBundle(resource("ca-cert.pem"))
                    .build())
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, mediaRoot))
            .segmentBasePath(tempDir.resolve("worker-segments"))
            .keepAliveTime(Duration.ofSeconds(10))
            .keepAliveTimeout(Duration.ofSeconds(2))
            .build();
    var engine =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder("ffmpeg"),
            new FakeFfmpegProcessManager(),
            new TranscodeCapabilityService(
                "ffmpeg",
                _ -> {
                  throw new IllegalStateException("Not used");
                }));
    return new TranscodeWorker(configuration, engine);
  }

  private Path resource(String name) throws URISyntaxException {
    var url = Objects.requireNonNull(getClass().getResource("/tls/" + name));
    return Path.of(url.toURI());
  }

  private static final class FreezableRelay implements AutoCloseable {

    private final ServerSocket listener;
    private final int targetPort;
    private final ExecutorService pumps = Executors.newVirtualThreadPerTaskExecutor();
    private final CopyOnWriteArrayList<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile boolean frozen;

    private FreezableRelay(int targetPort) throws IOException {
      this.targetPort = targetPort;
      listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      pumps.submit(this::acceptConnections);
    }

    private int port() {
      return listener.getLocalPort();
    }

    private void freeze() {
      frozen = true;
    }

    private void acceptConnections() {
      try {
        while (true) {
          var inbound = listener.accept();
          var outbound = new Socket(InetAddress.getLoopbackAddress(), targetPort);
          sockets.add(inbound);
          sockets.add(outbound);
          pumps.submit(() -> pump(inbound, outbound));
          pumps.submit(() -> pump(outbound, inbound));
        }
      } catch (IOException _) {
        // The listener was closed; the relay is shutting down.
      }
    }

    /** While frozen, bytes are consumed but never forwarded — a half-open connection. */
    private void pump(Socket from, Socket to) {
      try {
        var input = from.getInputStream();
        var output = to.getOutputStream();
        var buffer = new byte[8192];
        var read = 0;
        while ((read = input.read(buffer)) != -1) {
          if (frozen) {
            continue;
          }
          output.write(buffer, 0, read);
          output.flush();
        }
      } catch (IOException _) {
        // Either side closed; the pump ends.
      }
    }

    @Override
    public void close() throws IOException {
      listener.close();
      for (var socket : sockets) {
        socket.close();
      }
      pumps.shutdownNow();
    }
  }
}
