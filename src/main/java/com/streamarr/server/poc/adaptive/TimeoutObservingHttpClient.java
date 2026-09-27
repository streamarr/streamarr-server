package com.streamarr.server.poc.adaptive;

import com.streamarr.server.config.http.PocDelegatingHttpClient;
import com.streamarr.server.services.library.admission.adaptive.AdaptiveDropSignal;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Delegates every call to the TMDB client unchanged; a synchronous send that times out also marks
 * the admitted task on the calling thread as dropped. The metadata path only sends synchronously
 * and catches the timeout itself, so this is where the timeout is still visible.
 */
final class TimeoutObservingHttpClient extends HttpClient implements PocDelegatingHttpClient {

  private final HttpClient delegate;

  TimeoutObservingHttpClient(HttpClient delegate) {
    this.delegate = delegate;
  }

  @Override
  public HttpClient delegate() {
    return delegate;
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    try {
      return delegate.send(request, handler);
    } catch (HttpTimeoutException timeout) {
      AdaptiveDropSignal.markDropped();
      throw timeout;
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    return delegate.sendAsync(request, handler);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    return delegate.sendAsync(request, handler, pushPromiseHandler);
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return delegate.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return delegate.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return delegate.followRedirects();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return delegate.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return delegate.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return delegate.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return delegate.authenticator();
  }

  @Override
  public Version version() {
    return delegate.version();
  }

  @Override
  public Optional<Executor> executor() {
    return delegate.executor();
  }

  @Override
  public WebSocket.Builder newWebSocketBuilder() {
    return delegate.newWebSocketBuilder();
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public boolean awaitTermination(Duration duration) throws InterruptedException {
    return delegate.awaitTermination(duration);
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public void shutdownNow() {
    delegate.shutdownNow();
  }

  @Override
  public void close() {
    delegate.close();
  }
}
