package com.streamarr.server.services.library.walk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Throwaway benchmark: the scan enumerates ahead of a strategy that waits between pulls. */
@Tag("UnitTest")
@DisplayName("POC walk prefetch tests")
class WalkPrefetchTest {

  @Test
  @DisplayName("Should drain the whole walk while the consumer waits after its first pull")
  void shouldDrainTheWholeWalkWhileTheConsumerWaitsAfterItsFirstPull() {
    var pulled = new AtomicInteger();
    var source = Stream.iterate(0, i -> i < 1_000, i -> i + 1).peek(_ -> pulled.incrementAndGet());

    try (var prefetched = WalkPrefetch.drainAhead(source)) {
      var iterator = prefetched.iterator();
      assertThat(iterator.next()).isZero();

      await().atMost(Duration.ofSeconds(2)).until(() -> pulled.get() == 1_000);
      var rest = new ArrayList<Integer>();
      iterator.forEachRemaining(rest::add);
      assertThat(rest).hasSize(999).startsWith(1, 2, 3).endsWith(999);
    }
  }

  @Test
  @DisplayName("Should deliver the walk failure after the entries walked before it")
  void shouldDeliverTheWalkFailureAfterTheEntriesWalkedBeforeIt() {
    var failure = new UncheckedIOException(new IOException("walk broke"));
    var source =
        Stream.iterate(0, i -> i + 1)
            .peek(
                i -> {
                  if (i == 3) {
                    throw failure;
                  }
                });
    var seen = new ArrayList<Integer>();

    try (var prefetched = WalkPrefetch.drainAhead(source)) {
      assertThatThrownBy(() -> prefetched.forEach(seen::add)).isSameAs(failure);
    }

    assertThat(seen).containsExactly(0, 1, 2);
  }

  @Test
  @DisplayName("Should finish the walk when a folder is renamed after it was listed")
  void shouldFinishTheWalkWhenAFolderIsRenamedAfterItWasListed(@TempDir Path root)
      throws IOException {
    for (var i = 0; i < 50; i++) {
      var folder = Files.createDirectories(root.resolve("Movie %02d (2000)".formatted(i)));
      Files.createFile(folder.resolve("Movie %02d (2000).mkv".formatted(i)));
    }

    try (var walk = Files.walk(root);
        var prefetched = WalkPrefetch.drainAhead(walk.filter(Files::isRegularFile))) {
      var iterator = prefetched.iterator();
      var first = iterator.next();
      await().atMost(Duration.ofSeconds(2)).pollDelay(Duration.ofMillis(200)).until(() -> true);
      try (var folders = Files.list(root)) {
        for (var folder : folders.toList()) {
          if (!folder.equals(first.getParent())) {
            Files.move(folder, folder.resolveSibling(folder.getFileName() + " [renamed]"));
          }
        }
      }

      var rest = new ArrayList<Path>();
      iterator.forEachRemaining(rest::add);
      assertThat(rest).hasSize(49);
    }
  }
}
