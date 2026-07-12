package com.streamarr.transcode.worker;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@Tag("UnitTest")
@DisplayName("Worker gRPC Configuration Tests")
class WorkerGrpcConfigurationTest {

  @Test
  @DisplayName("Should own virtual thread executor while gRPC is enabled")
  void shouldOwnVirtualThreadExecutorWhileGrpcIsEnabled() throws Exception {
    ExecutorService executor;

    try (var context = enabledContext()) {
      executor = context.getBean("grpcServerExecutor", ExecutorService.class);
      var provider = context.getBean(GrpcServerExecutorProvider.class);

      assertThat(provider.getExecutor()).isSameAs(executor);
      assertThat(executor.submit(() -> Thread.currentThread().isVirtual()).get(1, SECONDS))
          .isTrue();
    }

    assertThat(executor.isShutdown()).isTrue();
  }

  private static AnnotationConfigApplicationContext enabledContext() {
    var context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of("spring.grpc.server.enabled=true").applyTo(context);
    context.register(WorkerGrpcConfiguration.class);
    context.refresh();
    return context;
  }
}
