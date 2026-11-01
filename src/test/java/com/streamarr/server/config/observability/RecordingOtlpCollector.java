package com.streamarr.server.config.observability;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * An OTLP/HTTP receiver that stands in for the collector. It parses every metric, trace and log
 * export it receives, and a test waits on those arrivals instead of polling for them.
 */
final class RecordingOtlpCollector implements AutoCloseable {

  private final HttpServer server;
  private final Exports<ExportMetricsServiceRequest> metricExports;
  private final Exports<ExportTraceServiceRequest> traceExports;
  private final Exports<ExportLogsServiceRequest> logExports;

  private RecordingOtlpCollector(HttpServer server, Duration bound) {
    this.server = server;
    this.metricExports = new Exports<>("metric", bound);
    this.traceExports = new Exports<>("trace", bound);
    this.logExports = new Exports<>("log", bound);
  }

  /** Starts a receiver on a free loopback port; each wait on it fails after {@code bound}. */
  static RecordingOtlpCollector start(Duration bound) {
    try {
      var collector =
          new RecordingOtlpCollector(
              HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0), bound);
      collector.receive(
          "/v1/metrics", collector.metricExports, ExportMetricsServiceRequest::parseFrom);
      collector.receive("/v1/traces", collector.traceExports, ExportTraceServiceRequest::parseFrom);
      collector.receive("/v1/logs", collector.logExports, ExportLogsServiceRequest::parseFrom);
      collector.server.start();
      return collector;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The receiver's base URL, ending in a slash as a hand-written endpoint may. */
  String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  Exports<ExportMetricsServiceRequest> metricExports() {
    return metricExports;
  }

  Exports<ExportTraceServiceRequest> traceExports() {
    return traceExports;
  }

  Exports<ExportLogsServiceRequest> logExports() {
    return logExports;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private <T> void receive(String path, Exports<T> exports, Parser<T> parser) {
    server.createContext(
        path,
        exchange -> {
          try (exchange) {
            exports.add(parser.parse(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
          }
        });
  }

  @FunctionalInterface
  private interface Parser<T> {
    T parse(byte[] body) throws IOException;
  }

  /** The exports of one signal, in the order they arrived. */
  static final class Exports<T> {

    private final String signal;
    private final Duration bound;
    private final List<T> received = new ArrayList<>();

    private Exports(String signal, Duration bound) {
      this.signal = signal;
      this.bound = bound;
    }

    /** The number of exports received so far: the position from which a later wait reads. */
    synchronized int position() {
      return received.size();
    }

    synchronized List<T> all() {
      return List.copyOf(received);
    }

    /**
     * Waits until the exports received after {@code position} satisfy {@code condition}, and
     * returns them. Fails when they do not within the receiver's bound.
     */
    synchronized List<T> awaitAfter(int position, Predicate<List<T>> condition)
        throws InterruptedException {
      var deadline = System.nanoTime() + bound.toNanos();
      while (!condition.test(after(position))) {
        var remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new AssertionError(
              "The %d %s exports received after position %d did not satisfy the condition within %s"
                  .formatted(received.size() - position, signal, position, bound));
        }

        TimeUnit.NANOSECONDS.timedWait(this, remaining);
      }

      return after(position);
    }

    private List<T> after(int position) {
      return List.copyOf(received.subList(position, received.size()));
    }

    private synchronized void add(T export) {
      received.add(export);
      notifyAll();
    }
  }
}
