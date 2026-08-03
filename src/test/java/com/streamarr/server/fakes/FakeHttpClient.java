package com.streamarr.server.fakes;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * An {@link HttpClient} that answers from a canned outcome instead of the network. An unresponsive
 * fake honours {@link HttpRequest#timeout()} the way the JDK client does — it waits out the
 * per-request timeout and then throws {@link HttpTimeoutException} — so a caller that leaves its
 * request unbounded waits {@link #UNBOUNDED_WAIT} instead.
 */
public class FakeHttpClient extends HttpClient {

  public static final Duration UNBOUNDED_WAIT = Duration.ofSeconds(10);

  private final Outcome outcome;
  private final AtomicInteger sendCount = new AtomicInteger();

  private FakeHttpClient(Outcome outcome) {
    this.outcome = outcome;
  }

  public static FakeHttpClient respondingWith(int statusCode) {
    return new FakeHttpClient(new Responding(statusCode));
  }

  public static FakeHttpClient failingWith(Exception exception) {
    return new FakeHttpClient(new Failing(exception));
  }

  public static FakeHttpClient unresponsive() {
    return new FakeHttpClient(new Unresponsive());
  }

  public int sendCount() {
    return sendCount.get();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
      throws IOException, InterruptedException {
    sendCount.incrementAndGet();

    return switch (outcome) {
      case Responding(int statusCode) -> new FakeHttpResponse<>(statusCode, request);
      case Failing(Exception exception) -> throw asSendFailure(exception);
      case Unresponsive _ -> throw waitOutTimeout(request);
    };
  }

  private IOException waitOutTimeout(HttpRequest request) throws InterruptedException {
    Thread.sleep(request.timeout().orElse(UNBOUNDED_WAIT));
    return new HttpTimeoutException("request timed out");
  }

  private IOException asSendFailure(Exception exception) throws InterruptedException {
    if (exception instanceof InterruptedException interrupted) {
      throw interrupted;
    }

    if (exception instanceof IOException failure) {
      return failure;
    }

    throw new IllegalArgumentException("Only IOException and InterruptedException can be faked");
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
    throw new UnsupportedOperationException("Asynchronous sending is not faked");
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> bodyHandler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    throw new UnsupportedOperationException("Asynchronous sending is not faked");
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return Optional.empty();
  }

  @Override
  public Redirect followRedirects() {
    return Redirect.NEVER;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return Optional.empty();
  }

  @Override
  public SSLContext sslContext() {
    throw new UnsupportedOperationException("TLS is not faked");
  }

  @Override
  public SSLParameters sslParameters() {
    return new SSLParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return Optional.empty();
  }

  @Override
  public Version version() {
    return Version.HTTP_1_1;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.empty();
  }

  @Override
  public WebSocket.Builder newWebSocketBuilder() {
    throw new UnsupportedOperationException("WebSockets are not faked");
  }

  private sealed interface Outcome permits Responding, Failing, Unresponsive {}

  private record Responding(int statusCode) implements Outcome {}

  private record Failing(Exception exception) implements Outcome {}

  private record Unresponsive() implements Outcome {}

  private record FakeHttpResponse<T>(int statusCode, HttpRequest request)
      implements HttpResponse<T> {

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of(), (_, _) -> true);
    }

    @Override
    public T body() {
      return null;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }
  }
}
