package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.lang.reflect.Proxy;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Objects;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Worker Identity Server Interceptor Tests")
class WorkerIdentityServerInterceptorTest {

  @Test
  @DisplayName("Should propagate downstream failure without rejecting authenticated call")
  void shouldPropagateDownstreamFailureWithoutRejectingAuthenticatedCall() throws Exception {
    var attributes =
        Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession(workerCertificate()))
            .build();
    var call = new RecordingServerCall(attributes);
    var interceptor =
        new WorkerIdentityServerInterceptor(new WorkerSpiffeIdentityMapper("streamarr.test"));
    var downstreamFailure = new IllegalStateException("downstream setup failed");
    ServerCallHandler<Object, Object> handler =
        (_, _) -> {
          throw downstreamFailure;
        };

    var thrown = catchThrowable(() -> interceptor.interceptCall(call, new Metadata(), handler));

    assertThat(thrown)
        .as("downstream failure must not be relabeled as %s", call.closedStatus())
        .isSameAs(downstreamFailure);
    assertThat(call.closedStatus()).isNull();
  }

  @Test
  @DisplayName("Should log the specific rejection cause when the worker identity is unmapped")
  void shouldLogTheSpecificRejectionCauseWhenTheWorkerIdentityIsUnmapped() throws Exception {
    var attributes =
        Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession(unmappedWorkerCertificate()))
            .build();
    var call = new RecordingServerCall(attributes);
    var interceptor =
        new WorkerIdentityServerInterceptor(new WorkerSpiffeIdentityMapper("streamarr.test"));
    var appender = attachAppender();

    interceptor.interceptCall(call, new Metadata(), (_, _) -> new ServerCall.Listener<>() {});

    assertThat(call.closedStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(call.closedStatus().getDescription()).isNull();
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("SPIFFE identity");
            });
  }

  @Test
  @DisplayName("Should log unexpected authentication failures at error with the stack trace")
  void shouldLogUnexpectedAuthenticationFailuresAtErrorWithTheStackTrace() {
    var attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, throwingSslSession()).build();
    var call = new RecordingServerCall(attributes);
    var interceptor =
        new WorkerIdentityServerInterceptor(new WorkerSpiffeIdentityMapper("streamarr.test"));
    var appender = attachAppender();

    interceptor.interceptCall(call, new Metadata(), (_, _) -> new ServerCall.Listener<>() {});

    assertThat(call.closedStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.ERROR);
              assertThat(event.getThrowableProxy()).isNotNull();
              assertThat(event.getThrowableProxy().getMessage()).contains("ssl session blew up");
            });
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    var logger = (Logger) LoggerFactory.getLogger(WorkerIdentityServerInterceptor.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static SSLSession throwingSslSession() {
    return (SSLSession)
        Proxy.newProxyInstance(
            SSLSession.class.getClassLoader(),
            new Class<?>[] {SSLSession.class},
            (_, _, _) -> {
              throw new IllegalStateException("ssl session blew up");
            });
  }

  private X509Certificate unmappedWorkerCertificate() throws Exception {
    try (var certificate =
        Objects.requireNonNull(getClass().getResourceAsStream("/tls/unmapped-worker-cert.pem"))) {
      return (X509Certificate)
          CertificateFactory.getInstance("X.509").generateCertificate(certificate);
    }
  }

  private X509Certificate workerCertificate() throws Exception {
    try (var certificate =
        Objects.requireNonNull(getClass().getResourceAsStream("/tls/worker-cert.pem"))) {
      return (X509Certificate)
          CertificateFactory.getInstance("X.509").generateCertificate(certificate);
    }
  }

  private static SSLSession sslSession(X509Certificate certificate) {
    return (SSLSession)
        Proxy.newProxyInstance(
            SSLSession.class.getClassLoader(),
            new Class<?>[] {SSLSession.class},
            (_, method, _) -> {
              if (method.getName().equals("getPeerCertificates")) {
                return new Certificate[] {certificate};
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static final class RecordingServerCall extends ServerCall<Object, Object> {

    private final Attributes attributes;
    private Status closedStatus;

    private RecordingServerCall(Attributes attributes) {
      this.attributes = attributes;
    }

    @Override
    public void request(int numMessages) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sendHeaders(Metadata headers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sendMessage(Object message) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close(Status status, Metadata trailers) {
      closedStatus = status;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public Attributes getAttributes() {
      return attributes;
    }

    @Override
    public MethodDescriptor<Object, Object> getMethodDescriptor() {
      throw new UnsupportedOperationException();
    }

    private Status closedStatus() {
      return closedStatus;
    }
  }
}
