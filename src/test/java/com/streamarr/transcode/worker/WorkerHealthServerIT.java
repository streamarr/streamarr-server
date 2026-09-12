package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("IntegrationTest")
@DisplayName("Worker Health Server Integration Tests")
class WorkerHealthServerIT {

  @Test
  @DisplayName("Should report live but not ready when initialized without an accepted session")
  void shouldReportLiveButNotReadyWhenInitializedWithoutAnAcceptedSession() throws Exception {
    try (var server = new WorkerHealthServer(0)) {
      server.start();
      var channel =
          NettyChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
      try {
        var health = HealthGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);

        assertThat(
                health
                    .check(HealthCheckRequest.newBuilder().setService("liveness").build())
                    .getStatus())
            .isEqualTo(ServingStatus.SERVING);
        assertThat(
                health
                    .check(HealthCheckRequest.newBuilder().setService("readiness").build())
                    .getStatus())
            .isEqualTo(ServingStatus.NOT_SERVING);
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  @DisplayName("Should answer health checks on a non-loopback address when serving Pod IP probes")
  void shouldAnswerHealthChecksOnANonLoopbackAddressWhenServingPodIpProbes() throws Exception {
    var address =
        NetworkInterface.networkInterfaces()
            .flatMap(NetworkInterface::inetAddresses)
            .filter(
                candidate -> candidate instanceof Inet4Address && !candidate.isLoopbackAddress())
            .findFirst()
            .orElseThrow();
    try (var server = new WorkerHealthServer(0)) {
      server.start();
      var channel =
          NettyChannelBuilder.forAddress(address.getHostAddress(), server.port())
              .usePlaintext()
              .build();
      try {
        var response =
            HealthGrpc.newBlockingStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .check(HealthCheckRequest.newBuilder().setService("liveness").build());

        assertThat(response.getStatus()).isEqualTo(ServingStatus.SERVING);
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }
}
