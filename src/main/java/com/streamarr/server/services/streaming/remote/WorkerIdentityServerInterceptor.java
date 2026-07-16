package com.streamarr.server.services.streaming.remote;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.security.cert.X509Certificate;
import java.util.UUID;
import javax.net.ssl.SSLPeerUnverifiedException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class WorkerIdentityServerInterceptor implements ServerInterceptor {

  static final Context.Key<UUID> AUTHENTICATED_WORKER_ID = Context.key("authenticated-worker-id");

  private final WorkerSpiffeIdentityMapper identityMapper;

  WorkerIdentityServerInterceptor(WorkerSpiffeIdentityMapper identityMapper) {
    this.identityMapper = identityMapper;
  }

  @Override
  public <R, S> ServerCall.Listener<R> interceptCall(
      ServerCall<R, S> call, Metadata headers, ServerCallHandler<R, S> next) {
    UUID workerId;
    try {
      var sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
      if (sslSession == null) {
        return reject(call, "no TLS session on transport");
      }
      var certificates = sslSession.getPeerCertificates();
      if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate leaf)) {
        return reject(call, "no X.509 peer certificate");
      }
      workerId = identityMapper.workerId(leaf);
    } catch (SSLPeerUnverifiedException | WorkerIdentityException e) {
      return reject(call, e.getMessage());
    } catch (RuntimeException e) {
      return reject(call, e.getMessage());
    }
    return Contexts.interceptCall(
        Context.current().withValue(AUTHENTICATED_WORKER_ID, workerId), call, headers, next);
  }

  private <R, S> ServerCall.Listener<R> reject(ServerCall<R, S> call, String reason) {
    log.warn("Rejecting unauthenticated worker call: {}", reason);
    call.close(Status.UNAUTHENTICATED, new Metadata());
    return new ServerCall.Listener<>() {};
  }
}
