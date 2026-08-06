package com.streamarr.server.fixtures;

import com.streamarr.server.services.streaming.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegProcessManager;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegTranscodeEngine;
import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import com.streamarr.transcode.tls.PemTlsIdentity;
import com.streamarr.transcode.worker.TranscodeWorkerConfiguration;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public final class RemoteWorkerFixtures {

  public static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  public static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  private RemoteWorkerFixtures() {}

  public static Path tlsResource(String name) throws URISyntaxException {
    var url = Objects.requireNonNull(RemoteWorkerFixtures.class.getResource("/tls/" + name));
    return Path.of(url.toURI());
  }

  public static PemTlsIdentity tlsIdentity(String certificate, String privateKey)
      throws URISyntaxException {
    return PemTlsIdentity.builder()
        .certificate(tlsResource(certificate))
        .privateKey(tlsResource(privateKey))
        .trustBundle(tlsResource("ca-cert.pem"))
        .build();
  }

  public static WorkerSessionServerConfiguration.WorkerSessionServerConfigurationBuilder
      serverConfigurationBuilder() throws URISyntaxException {
    return WorkerSessionServerConfiguration.builder()
        .port(0)
        .trustDomain("streamarr.test")
        .tlsIdentity(tlsIdentity("server-cert.pem", "server-key.fixture"));
  }

  public static TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder
      workerConfigurationBuilder() throws URISyntaxException {
    return TranscodeWorkerConfiguration.builder()
        .workerId(WORKER_ID)
        .bootId(UUID.randomUUID())
        .tlsIdentity(tlsIdentity("worker-cert.pem", "worker-key.fixture"));
  }

  public static FfmpegTranscodeEngine remuxEngine(FfmpegProcessManager processManager) {
    return new FfmpegTranscodeEngine(
        new FfmpegCommandBuilder("ffmpeg"),
        processManager,
        new TranscodeCapabilityService(
            "ffmpeg",
            _ -> {
              throw new IllegalStateException("Not used for remux");
            }));
  }
}
