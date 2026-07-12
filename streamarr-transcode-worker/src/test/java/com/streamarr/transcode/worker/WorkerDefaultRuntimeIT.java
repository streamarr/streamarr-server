package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

@Tag("IntegrationTest")
@DisplayName("Worker Default Runtime Integration Tests")
class WorkerDefaultRuntimeIT {

  @Test
  @DisplayName("Should start without distributed resources when unenrolled")
  void shouldStartWithoutDistributedResourcesWhenUnenrolled() throws IOException {
    try (var reservedPort = reserveLoopbackPort()) {
      var application =
          new SpringApplicationBuilder(
              StreamarrTranscodeWorkerApplication.class, ProbeServiceConfiguration.class);

      try (var context =
          application.run("--spring.grpc.server.port=" + reservedPort.getLocalPort())) {
        assertThat(context).isInstanceOf(GenericApplicationContext.class);
        assertThat(context.getEnvironment().getProperty("spring.main.web-application-type"))
            .isEqualTo("none");
        assertThat(context.getEnvironment().getProperty("spring.grpc.server.address"))
            .isEqualTo("127.0.0.1");
        assertThat(context.containsBean("taskScheduler")).isFalse();
        assertThat(context.containsBean("grpcServerReflectionService")).isFalse();
        assertThat(context.containsBean("grpcServerHealthService")).isFalse();
        assertThat(context.containsBean("grpcServerExecutor")).isFalse();
        assertDisabled(context, "spring.grpc.server.enabled");
        assertDisabled(context, "spring.grpc.server.reflection.enabled");
        assertDisabled(context, "spring.grpc.server.health.enabled");
        assertDisabled(context, "spring.grpc.server.health.schedule.enabled");
        assertThat(context.getBeansOfType(GrpcServerFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(GrpcServerLifecycle.class)).isEmpty();
        assertThat(context.getBeansOfType(GrpcServerExecutorProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(Executor.class)).isEmpty();
      }
    }
  }

  private static ServerSocket reserveLoopbackPort() throws IOException {
    var socket = new ServerSocket();
    socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    return socket;
  }

  private static void assertDisabled(ConfigurableApplicationContext context, String propertyName) {
    assertThat(context.getEnvironment().getProperty(propertyName, Boolean.class)).isFalse();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeServiceConfiguration {

    @Bean
    BindableService listenerProbe() {
      return () -> ServerServiceDefinition.builder("streamarr.worker.ListenerProbe").build();
    }
  }
}
