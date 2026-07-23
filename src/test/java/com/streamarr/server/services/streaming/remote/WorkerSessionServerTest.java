package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.transcode.tls.PemTlsIdentity;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Session Server Tests")
class WorkerSessionServerTest {

  private WorkerSessionServer unstartedServer() {
    var configuration =
        WorkerSessionServerConfiguration.builder()
            .port(0)
            .trustDomain("streamarr.test")
            .tlsIdentity(
                PemTlsIdentity.builder()
                    .certificate(Path.of("unused-cert.pem"))
                    .privateKey(Path.of("unused-key.pem"))
                    .trustBundle(Path.of("unused-ca.pem"))
                    .build())
            .build();
    return new WorkerSessionServer(configuration, new FakeSegmentStore());
  }

  @Test
  @DisplayName("Should report no capability when the server has not been started")
  void shouldReportNoCapabilityWhenTheServerHasNotBeenStarted() {
    var server = unstartedServer();
    var sourceNamespaceId = UUID.randomUUID();

    assertThat(server.eligibleWorkers(sourceNamespaceId)).isEmpty();
    assertThat(server.hasConnectedWorker(sourceNamespaceId)).isFalse();
    assertThat(server.stopVariant(UUID.randomUUID(), "720p")).isFalse();
  }

  @Test
  @DisplayName("Should require a started server when dispatch capability is demanded")
  void shouldRequireAStartedServerWhenDispatchCapabilityIsDemanded() {
    var server = unstartedServer();
    var sourceNamespaceId = UUID.randomUUID();

    assertThatThrownBy(() -> server.availableSlots(sourceNamespaceId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not started");
  }

  @Test
  @DisplayName("Should close idempotently when the server was never started")
  void shouldCloseIdempotentlyWhenTheServerWasNeverStarted() {
    var server = unstartedServer();

    assertThatNoException()
        .isThrownBy(
            () -> {
              server.close();
              server.close();
            });
  }
}
