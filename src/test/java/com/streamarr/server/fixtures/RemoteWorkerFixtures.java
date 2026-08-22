package com.streamarr.server.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.streaming.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegProcessManager;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegTranscodeEngine;
import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import com.streamarr.transcode.tls.PemTlsIdentity;
import com.streamarr.transcode.worker.TranscodeWorkerConfiguration;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

public final class RemoteWorkerFixtures {

  public static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  public static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  private RemoteWorkerFixtures() {}

  public static Path tlsResource(String name) throws URISyntaxException {
    var url = RemoteWorkerFixtures.class.getResource("/tls/" + name);
    assertThat(url).as("TLS resource %s must exist", name).isNotNull();
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
    var capabilityService =
        new TranscodeCapabilityService(
            "ffmpeg",
            command ->
                new CompatibleFfmpegProcess(
                    Arrays.asList(command).contains("muxer=hls") ? "hls_segment_options" : ""));
    capabilityService.detectCapabilities();

    return new FfmpegTranscodeEngine(
        new FfmpegCommandBuilder("ffmpeg"), processManager, capabilityService);
  }

  private static class CompatibleFfmpegProcess extends Process {

    private final InputStream inputStream;

    CompatibleFfmpegProcess(String stdout) {
      inputStream = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return inputStream;
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
      // no-op for test fake
    }
  }
}
