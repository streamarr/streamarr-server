package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A TCP proxy in front of PostgreSQL that carries every connection at once except a cancel
 * request's, which it holds until the test releases it, as a network that stalls the cancel
 * request's own connection would. Closing the proxy closes every connection it carries.
 */
public final class HeldCancelRequests implements AutoCloseable {

  // A cancel request's connection starts with the message length and then the CancelRequest code
  // where a startup message has its protocol version (PostgreSQL protocol, "Message Formats").
  private static final int HEADER_LENGTH = 8;
  private static final int CANCEL_REQUEST_CODE = 80877102;
  private static final Duration BOUND = Duration.ofSeconds(10);

  private final String databaseHost;
  private final int databasePort;
  private final String databasePathAndQuery;
  private final ServerSocket listener;
  private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final CountDownLatch release = new CountDownLatch(1);
  private final Semaphore heldCancelRequests = new Semaphore(0);

  private HeldCancelRequests(URI database) throws IOException {
    databaseHost = database.getHost();
    databasePort = database.getPort();
    databasePathAndQuery =
        database.getRawPath()
            + Optional.ofNullable(database.getRawQuery()).map("?"::concat).orElse("");
    listener = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
    connections.execute(this::acceptConnections);
  }

  /** Starts a proxy in front of the database that {@code jdbcUrl} names. */
  public static HeldCancelRequests inFrontOf(String jdbcUrl) throws IOException {
    return new HeldCancelRequests(URI.create(jdbcUrl.substring("jdbc:".length())));
  }

  /** Returns a JDBC URL that reaches the database through this proxy. */
  public String jdbcUrl() {
    return "jdbc:postgresql://"
        + listener.getInetAddress().getHostAddress()
        + ":"
        + listener.getLocalPort()
        + databasePathAndQuery;
  }

  /** Waits until the proxy holds a cancel request, failing after ten seconds. */
  public void awaitHeldCancelRequest() throws InterruptedException {
    assertThat(heldCancelRequests.tryAcquire(BOUND.toMillis(), TimeUnit.MILLISECONDS))
        .as("The proxy holds a cancel request")
        .isTrue();
  }

  /** Lets every held cancel request, and every later one, through to PostgreSQL. */
  public void release() {
    release.countDown();
  }

  @Override
  public void close() throws IOException {
    listener.close();
    for (var socket : sockets) {
      socket.close();
    }

    connections.shutdownNow();
    connections.close();
  }

  private void acceptConnections() {
    while (!listener.isClosed()) {
      try {
        var client = listener.accept();
        sockets.add(client);
        connections.execute(() -> carry(client));
      } catch (IOException _) {
        // The proxy closed.
      }
    }
  }

  private void carry(Socket client) {
    try (client) {
      var header = client.getInputStream().readNBytes(HEADER_LENGTH);
      if (isCancelRequest(header)) {
        heldCancelRequests.release();
        release.await();
      }

      try (var database = new Socket(databaseHost, databasePort)) {
        sockets.add(database);
        database.getOutputStream().write(header);
        connections.execute(() -> pipe(database, client));
        pipe(client, database);
      }
    } catch (IOException _) {
      // The connection ended, or the proxy closed it.
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean isCancelRequest(byte[] header) {
    return header.length == HEADER_LENGTH
        && ByteBuffer.wrap(header).getInt(Integer.BYTES) == CANCEL_REQUEST_CODE;
  }

  // Copies until the sender ends its stream, then ends the stream to the receiver.
  private static void pipe(Socket sender, Socket receiver) {
    try {
      sender.getInputStream().transferTo(receiver.getOutputStream());
      receiver.shutdownOutput();
    } catch (IOException _) {
      // One side closed its connection.
    }
  }
}
