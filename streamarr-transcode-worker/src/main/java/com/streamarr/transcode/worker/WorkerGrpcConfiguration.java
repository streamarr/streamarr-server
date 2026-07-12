package com.streamarr.transcode.worker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.grpc.server", name = "enabled", havingValue = "true")
class WorkerGrpcConfiguration {

  @Bean(destroyMethod = "close")
  ExecutorService grpcServerExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  GrpcServerExecutorProvider grpcServerExecutorProvider(
      @Qualifier("grpcServerExecutor") ExecutorService executor) {
    return () -> executor;
  }
}
