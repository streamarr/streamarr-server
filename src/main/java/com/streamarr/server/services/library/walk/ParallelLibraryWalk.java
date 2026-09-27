package com.streamarr.server.services.library.walk;

import com.streamarr.server.services.validation.IgnoredFileValidator;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Lever {@code poc.walk=parallel}: lists every directory on its own virtual thread and hands each
 * regular, non-ignored file to the scan through a {@link SynchronousQueue}. A walker blocks until
 * the scan pulls its file. A walker reads its whole directory, starts a walker per subdirectory,
 * closes the directory and only then hands its files over one at a time.
 *
 * <p>The directory reads themselves ({@code opendir}, {@code readdir}, {@code lstat}) run on a
 * pool of {@code poc.walk.listing-threads} platform threads (default {@value
 * #DEFAULT_LISTING_THREADS}) while the walker parks. The JDK does not compensate a carrier for
 * these native calls, so reading on the walkers themselves would pin every carrier for the whole
 * walk on a slow file system (SMB, a container bind mount) and delay every other virtual thread,
 * request handlers included. The pool sets how many reads are outstanding; before, the carrier
 * count did.
 *
 * <p>The stream holds the same set of paths that {@code Files.walk(root)} followed by the scan's
 * {@code Files::isRegularFile} and ignore filters holds: links are not followed into directories
 * (the root included), a link to a regular file counts as a file. Only the order differs. The first
 * I/O failure ends the stream with an {@link UncheckedIOException}, as {@code Files.walk} does;
 * files other walkers handed over before it stay handed over.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "poc.walk", havingValue = "parallel")
public class ParallelLibraryWalk implements LibraryWalk {

  static final int DEFAULT_LISTING_THREADS = 32;

  private final IgnoredFileValidator ignoredFileValidator;
  private final int listingThreads;

  public ParallelLibraryWalk(IgnoredFileValidator ignoredFileValidator) {
    this(ignoredFileValidator, DEFAULT_LISTING_THREADS);
  }

  @Autowired
  public ParallelLibraryWalk(
      IgnoredFileValidator ignoredFileValidator,
      @Value("${poc.walk.listing-threads:" + DEFAULT_LISTING_THREADS + "}") int listingThreads) {
    if (listingThreads < 1) {
      throw new IllegalArgumentException("poc.walk.listing-threads must be positive");
    }

    this.ignoredFileValidator = ignoredFileValidator;
    this.listingThreads = listingThreads;
  }

  @Override
  public String name() {
    return "parallel";
  }

  @Override
  public Stream<Path> walk(Path root) throws IOException {
    var attributes =
        Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isDirectory()) {
      return Stream.of(root);
    }

    var walk = new Walk(root);
    walk.start();
    return StreamSupport.stream(walk, false).onClose(walk::close);
  }

  private sealed interface Handoff {
    record File(Path path) implements Handoff {}

    record Failure(RuntimeException exception) implements Handoff {}

    record End() implements Handoff {}
  }

  private final class Walk implements Spliterator<Path> {

    private final Path root;
    private final SynchronousQueue<Handoff> handoff = new SynchronousQueue<>();
    private final AtomicInteger pendingDirectories = new AtomicInteger();
    private final AtomicInteger liveWalkers = new AtomicInteger();
    private final AtomicInteger peakWalkers = new AtomicInteger();
    private final LongAdder directoriesListed = new LongAdder();
    private final LongAdder filesHanded = new LongAdder();
    private final ExecutorService walkers =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("poc-walk-", 0).factory());
    private final ExecutorService listing =
        Executors.newFixedThreadPool(
            listingThreads, Thread.ofPlatform().daemon().name("poc-walk-io-", 0).factory());
    private final AtomicInteger liveReads = new AtomicInteger();
    private final AtomicInteger peakReads = new AtomicInteger();
    private final long startedNanos = System.nanoTime();
    private volatile boolean closed;
    private boolean finished;

    private Walk(Path root) {
      this.root = root;
    }

    private void start() {
      spawn(root);
    }

    private void close() {
      closed = true;
      walkers.shutdownNow();
      listing.shutdownNow();
    }

    private void spawn(Path directory) {
      pendingDirectories.incrementAndGet();
      try {
        walkers.execute(() -> walkDirectory(directory));
      } catch (RejectedExecutionException _) {
        // Closed: nobody waits for the end marker any more.
        pendingDirectories.decrementAndGet();
      }
    }

    private void walkDirectory(Path directory) {
      peakWalkers.accumulateAndGet(liveWalkers.incrementAndGet(), Math::max);
      try {
        for (var file : listDirectory(directory)) {
          hand(new Handoff.File(file));
          filesHanded.increment();
        }
      } catch (IOException e) {
        handQuietly(new Handoff.Failure(new UncheckedIOException(e)));
      } catch (RuntimeException e) {
        handQuietly(new Handoff.Failure(e));
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      } finally {
        liveWalkers.decrementAndGet();
        if (pendingDirectories.decrementAndGet() == 0) {
          handQuietly(new Handoff.End());
        }
      }
    }

    /** Reads the directory on the listing pool; the calling walker parks meanwhile. */
    private List<Path> listDirectory(Path directory) throws IOException, InterruptedException {
      Future<List<Path>> read;
      try {
        read = listing.submit(() -> readOnListingThread(directory));
      } catch (RejectedExecutionException _) {
        throw new InterruptedException("Library walk closed");
      }

      try {
        return read.get();
      } catch (InterruptedException e) {
        read.cancel(true);
        throw e;
      } catch (ExecutionException e) {
        throw rethrowable(e.getCause());
      }
    }

    private static IOException rethrowable(Throwable cause) {
      if (cause instanceof IOException io) {
        return io;
      }

      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }

      if (cause instanceof Error error) {
        throw error;
      }

      return new IOException(cause);
    }

    private List<Path> readOnListingThread(Path directory) throws IOException {
      peakReads.accumulateAndGet(liveReads.incrementAndGet(), Math::max);
      try {
        return readDirectory(directory);
      } finally {
        liveReads.decrementAndGet();
      }
    }

    private List<Path> readDirectory(Path directory) throws IOException {
      var files = new ArrayList<Path>();
      try (var entries = Files.newDirectoryStream(directory)) {
        for (var entry : entries) {
          var attributes =
              Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (attributes.isDirectory()) {
            spawn(entry);
            continue;
          }

          if (isScanCandidate(entry, attributes)) {
            files.add(entry);
          }
        }
      } catch (DirectoryIteratorException e) {
        throw e.getCause();
      }

      directoriesListed.increment();
      return files;
    }

    private boolean isScanCandidate(Path entry, BasicFileAttributes attributes) {
      var regular =
          attributes.isSymbolicLink() ? Files.isRegularFile(entry) : attributes.isRegularFile();
      return regular && !ignoredFileValidator.shouldIgnore(entry);
    }

    private void hand(Handoff item) throws InterruptedException {
      if (closed) {
        throw new InterruptedException("Library walk closed");
      }

      handoff.put(item);
    }

    private void handQuietly(Handoff item) {
      try {
        hand(item);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public boolean tryAdvance(Consumer<? super Path> action) {
      if (finished) {
        return false;
      }

      return switch (take()) {
        case Handoff.File(var path) -> {
          action.accept(path);
          yield true;
        }
        case Handoff.Failure(var exception) -> {
          finished = true;
          close();
          throw exception;
        }
        case Handoff.End _ -> {
          finished = true;
          listing.shutdown();
          log.info(
              "POC parallel walk of {} handed over {} files from {} directories in {} ms; peak {}"
                  + " concurrent walkers, peak {} concurrent directory reads on {} listing"
                  + " threads.",
              root,
              filesHanded.sum(),
              directoriesListed.sum(),
              (System.nanoTime() - startedNanos) / 1_000_000,
              peakWalkers.get(),
              peakReads.get(),
              listingThreads);
          yield false;
        }
      };
    }

    private Handoff take() {
      try {
        return handoff.take();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        close();
        throw new UncheckedIOException(new InterruptedIOException("Library walk interrupted"));
      }
    }

    @Override
    public Spliterator<Path> trySplit() {
      return null;
    }

    @Override
    public long estimateSize() {
      return Long.MAX_VALUE;
    }

    @Override
    public int characteristics() {
      return Spliterator.DISTINCT | Spliterator.NONNULL;
    }
  }
}
