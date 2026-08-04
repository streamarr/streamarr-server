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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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
  private static final CountDownLatch NEVER_RESPONDS = new CountDownLatch(1);

  private final Outcome outcome;
  private final AtomicInteger sendCount = new AtomicInteger();

  private FakeHttpClient(Outcome outcome) {
    this.outcome = outcome;
  }

  public static FakeHttpClient respondingWith(int statusCode) {
    return new FakeHttpClient(new Responding(statusCode));
  }

  public static FakeHttpClient failingWith(IOException exception) {
    return new FakeHttpClient(new IoFailure(exception));
  }

  public static FakeHttpClient failingWith(InterruptedException exception) {
    return new FakeHttpClient(new InterruptedFailure(exception));
  }

  public static FakeHttpClient unresponsive() {
    return new FakeHttpClient(new Unresponsive());
  }

  public static ControlledResponses respondingWithBlockedFirst(
      int firstStatusCode, int subsequentStatusCode) {
    var firstRequestStarted = new CountDownLatch(1);
    var releaseFirstResponse = new CountDownLatch(1);
    var client =
        new FakeHttpClient(
            new BlockedFirstResponse(
                firstStatusCode, subsequentStatusCode, firstRequestStarted, releaseFirstResponse));
    return new ControlledResponses(client, firstRequestStarted, releaseFirstResponse);
  }

  public int sendCount() {
    return sendCount.get();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
      throws IOException, InterruptedException {
    var requestNumber = sendCount.incrementAndGet();

    return switch (outcome) {
      case Responding(int statusCode) -> new FakeHttpResponse<>(statusCode, request);
      case IoFailure(IOException exception) -> throw exception;
      case InterruptedFailure(InterruptedException exception) -> throw exception;
      case Unresponsive _ -> throw waitOutTimeout(request);
      case BlockedFirstResponse response -> response.answer(requestNumber, request);
    };
  }

  private IOException waitOutTimeout(HttpRequest request) throws InterruptedException {
    NEVER_RESPONDS.await(request.timeout().orElse(UNBOUNDED_WAIT).toNanos(), TimeUnit.NANOSECONDS);
    return new HttpTimeoutException("request timed out");
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

  private sealed interface Outcome
      permits Responding, IoFailure, InterruptedFailure, Unresponsive, BlockedFirstResponse {}

  private record Responding(int statusCode) implements Outcome {}

  private record IoFailure(IOException exception) implements Outcome {}

  private record InterruptedFailure(InterruptedException exception) implements Outcome {}

  private record Unresponsive() implements Outcome {}

  private record BlockedFirstResponse(
      int firstStatusCode,
      int subsequentStatusCode,
      CountDownLatch firstRequestStarted,
      CountDownLatch releaseFirstResponse)
      implements Outcome {

    private <T> HttpResponse<T> answer(int requestNumber, HttpRequest request)
        throws InterruptedException {
      if (requestNumber != 1) {
        return new FakeHttpResponse<>(subsequentStatusCode, request);
      }

      firstRequestStarted.countDown();
      releaseFirstResponse.await();
      return new FakeHttpResponse<>(firstStatusCode, request);
    }
  }

  public record ControlledResponses(
      FakeHttpClient client,
      CountDownLatch firstRequestStarted,
      CountDownLatch releaseFirstResponseSignal) {

    public boolean awaitFirstRequest(Duration timeout) throws InterruptedException {
      return firstRequestStarted.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void releaseFirstResponse() {
      releaseFirstResponseSignal.countDown();
    }
  }

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
