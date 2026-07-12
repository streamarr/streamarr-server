package com.streamarr.transcode.engine.segment;

import com.streamarr.transcode.engine.error.TranscodeException;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocalSegmentStorage {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
  private static final String STAGING_DIRECTORY = ".staging";

  private record SegmentPublication(
      TranscodeJobRef jobRef, Optional<Path> currentRoot, List<Path> historyRoots) {

    private List<Path> searchRoots() {
      if (currentRoot.isEmpty()) {
        return List.of();
      }
      var searchRoots = new ArrayList<Path>();
      searchRoots.add(currentRoot.orElseThrow());
      searchRoots.addAll(historyRoots);
      return List.copyOf(searchRoots);
    }
  }

  private record SegmentPath(Path root, Path path) {}

  private final Path baseDir;
  private final ConcurrentHashMap<UUID, Path> sessionDirs = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, SegmentGeneration> currentCandidates =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, SegmentPublication> publishedGenerations =
      new ConcurrentHashMap<>();

  public LocalSegmentStorage(Path baseDir) {
    this.baseDir = baseDir;
  }

  public byte[] readSegment(UUID sessionId, String segmentName) {
    NoSuchFileException missing = null;
    for (var segmentPath : resolveSegmentPaths(sessionId, segmentName)) {
      try {
        return Files.readAllBytes(verifiedSegmentPath(segmentPath, segmentName));
      } catch (NoSuchFileException exception) {
        missing = exception;
      } catch (IOException exception) {
        throw new UncheckedIOException("Failed to read segment: " + segmentName, exception);
      }
    }
    throw new TranscodeException("Segment not found: " + segmentName, missing);
  }

  public boolean waitForSegment(UUID sessionId, String segmentName, Duration timeout) {
    resolveWithin(baseDir, segmentName);
    var deadline = System.nanoTime() + timeout.toNanos();

    while (System.nanoTime() < deadline) {
      if (segmentExists(sessionId, segmentName)) {
        return true;
      }
      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return segmentExists(sessionId, segmentName);
  }

  public boolean segmentExists(UUID sessionId, String segmentName) {
    try {
      return anyExists(resolveSegmentPaths(sessionId, segmentName));
    } catch (TranscodeException exception) {
      return false;
    }
  }

  public Path getOutputDirectory(UUID sessionId) {
    return sessionDirs.computeIfAbsent(sessionId, this::createSessionDirectory);
  }

  public Path getOutputDirectory(UUID sessionId, String variantLabel) {
    var sessionDir = getOutputDirectory(sessionId);
    var variantDir = sessionDir.resolve(variantLabel);

    try {
      Files.createDirectories(variantDir);
      return variantDir;
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to create variant directory", exception);
    }
  }

  public SegmentGeneration prepareGeneration(UUID sessionId, TranscodeJobRef jobRef) {
    var outputDirectory =
        baseDir
            .resolve(STAGING_DIRECTORY)
            .resolve(sessionId.toString())
            .resolve(jobRef.jobId() + "-" + jobRef.generation());

    try {
      rejectResidualArtifacts(outputDirectory);
      Files.createDirectories(outputDirectory);
      var generation = new SegmentGeneration(sessionId, jobRef, outputDirectory);
      currentCandidates.put(sessionId, generation);
      return generation;
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to prepare segment generation", exception);
    }
  }

  public void publish(SegmentGeneration generation) {
    currentCandidates.compute(
        generation.sessionId(),
        (sessionId, currentCandidate) -> {
          if (currentCandidate != generation) {
            throw new IllegalStateException("Segment generation is no longer current");
          }
          publishedGenerations.compute(
              sessionId,
              (_, currentPublication) ->
                  new SegmentPublication(
                      generation.jobRef(),
                      Optional.of(generation.outputDirectory()),
                      publicationHistory(currentPublication, sessionDirs.get(sessionId))));
          return currentCandidate;
        });
  }

  public void discard(SegmentGeneration generation) {
    var discarded = new AtomicBoolean();
    currentCandidates.computeIfPresent(
        generation.sessionId(),
        (_, currentCandidate) -> {
          if (currentCandidate != generation) {
            return currentCandidate;
          }
          var publication = publishedGenerations.get(generation.sessionId());
          if (publication != null
              && publication.jobRef().equals(generation.jobRef())
              && publication
                  .currentRoot()
                  .filter(generation.outputDirectory()::equals)
                  .isPresent()) {
            return currentCandidate;
          }
          discarded.set(true);
          return null;
        });
    if (!discarded.get()) {
      throw new IllegalStateException("Segment generation is not owned by this storage");
    }
    var outputDirectory = generation.outputDirectory();
    if (Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
      deleteDirectoryRecursively(outputDirectory);
    }
  }

  public boolean withdraw(UUID sessionId, TranscodeJobRef jobRef) {
    var withdrawn = new AtomicBoolean();
    publishedGenerations.computeIfPresent(
        sessionId,
        (_, current) -> {
          if (!current.jobRef().equals(jobRef)) {
            return current;
          }
          withdrawn.set(true);
          return new SegmentPublication(current.jobRef(), Optional.empty(), current.historyRoots());
        });
    return withdrawn.get();
  }

  public Set<UUID> snapshotStoredSessionIds() {
    var sessionIds = new HashSet<UUID>();
    try {
      addStoredSessionIds(baseDir, sessionIds);
      addStoredSessionIds(baseDir.resolve(STAGING_DIRECTORY), sessionIds);
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to discover stored stream sessions", exception);
    }
    return Set.copyOf(sessionIds);
  }

  public void deleteSession(UUID sessionId) {
    currentCandidates.remove(sessionId);
    publishedGenerations.remove(sessionId);
    sessionDirs.remove(sessionId);
    deleteIfExists(baseDir.resolve(sessionId.toString()));
    deleteIfExists(baseDir.resolve(STAGING_DIRECTORY).resolve(sessionId.toString()));
  }

  public void shutdown() {
    currentCandidates.clear();
    publishedGenerations.clear();
    sessionDirs.clear();
  }

  private List<SegmentPath> resolveSegmentPaths(UUID sessionId, String segmentName) {
    var publication = publishedGenerations.get(sessionId);
    var searchRoots =
        publication == null
            ? Optional.ofNullable(sessionDirs.get(sessionId)).map(List::of).orElseGet(List::of)
            : publication.searchRoots();
    if (searchRoots.isEmpty()) {
      throw new TranscodeException("No output directory for session: " + sessionId);
    }

    return searchRoots.stream()
        .map(searchRoot -> new SegmentPath(searchRoot, resolveWithin(searchRoot, segmentName)))
        .toList();
  }

  private static Path resolveWithin(Path searchRoot, String segmentName) {
    var resolved = searchRoot.resolve(segmentName).normalize();
    if (!resolved.startsWith(searchRoot)) {
      throw new InvalidSegmentPathException(segmentName);
    }
    return resolved;
  }

  private static boolean anyExists(List<SegmentPath> paths) {
    for (var path : paths) {
      try {
        verifiedSegmentPath(path, path.path().getFileName().toString());
        return true;
      } catch (NoSuchFileException _) {
        // Continue through older published roots.
      } catch (IOException exception) {
        throw new UncheckedIOException("Failed to inspect segment", exception);
      }
    }
    return false;
  }

  private static Path verifiedSegmentPath(SegmentPath segmentPath, String segmentName)
      throws IOException {
    var realRoot = segmentPath.root().toRealPath();
    var realSegment = segmentPath.path().toRealPath();
    if (!realSegment.startsWith(realRoot) || !Files.isRegularFile(realSegment)) {
      throw new InvalidSegmentPathException(segmentName);
    }
    return realSegment;
  }

  private static List<Path> publicationHistory(SegmentPublication current, Path legacyRoot) {
    if (current == null) {
      return legacyRoot == null ? List.of() : List.of(legacyRoot);
    }
    var historyRoots = new ArrayList<Path>();
    current.currentRoot().ifPresent(historyRoots::add);
    historyRoots.addAll(current.historyRoots());
    return List.copyOf(historyRoots);
  }

  private Path createSessionDirectory(UUID sessionId) {
    var dir = baseDir.resolve(sessionId.toString());

    try {
      Files.createDirectories(dir);
      return dir;
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to create session directory", exception);
    }
  }

  private static void rejectResidualArtifacts(Path outputDirectory) throws IOException {
    if (!Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (!Files.isDirectory(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Segment generation path is not a directory");
    }
    try (var entries = Files.newDirectoryStream(outputDirectory)) {
      if (entries.iterator().hasNext()) {
        throw new IllegalStateException("Segment generation contains residual artifacts");
      }
    }
  }

  private static Optional<UUID> storedSessionId(Path entry) {
    var name = entry.getFileName().toString();
    try {
      var sessionId = UUID.fromString(name);
      return sessionId.toString().equals(name) ? Optional.of(sessionId) : Optional.empty();
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  private static void addStoredSessionIds(Path directory, Set<UUID> sessionIds) throws IOException {
    try (var entries = Files.newDirectoryStream(directory)) {
      for (var entry : entries) {
        storedSessionId(entry).ifPresent(sessionIds::add);
      }
    } catch (NoSuchFileException _) {
      // A missing storage layout has no sessions to discover.
    }
  }

  private void deleteDirectoryRecursively(Path dir) {
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                throws IOException {
              if (exception != null) {
                throw exception;
              }
              Files.deleteIfExists(directory);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to clean up session directory: " + dir, exception);
    }
  }

  private void deleteIfExists(Path path) {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      deleteDirectoryRecursively(path);
    }
  }
}
