package com.streamarr.server.fakes;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A FileSystem wrapper that throws a configurable RuntimeException during Files.walk() iteration.
 * Used to test error handling when file system traversal fails mid-stream, and to run a hook before
 * each access check or after each attribute read. Its directory streams are secure where the
 * wrapped file system's are.
 *
 * <p>Jimfs cannot simulate permission errors, so this wrapper intercepts directory stream iteration
 * and throws the configured exception after each directory stream returns a set number of entries.
 * The constructors set that number to one.
 */
public class ThrowingFileSystemWrapper extends FileSystem {

  private final FileSystem delegate;
  private final ThrowingFileSystemProvider throwingProvider;
  private final Supplier<RuntimeException> exceptionSupplier;
  private final int entriesBeforeFailure;
  private final Function<Path, Optional<IOException>> laterAttributeReadFailure;
  private final Set<Path> pathsWithReadAttributes = ConcurrentHashMap.newKeySet();
  private final Hooks hooks;

  public ThrowingFileSystemWrapper(FileSystem delegate) {
    this(
        delegate,
        () ->
            new UncheckedIOException(
                new IOException("Simulated permission denied during directory traversal")));
  }

  public ThrowingFileSystemWrapper(
      FileSystem delegate, Supplier<RuntimeException> exceptionSupplier) {
    this(delegate, exceptionSupplier, 1);
  }

  /** Returns a wrapper whose directory streams fail before they return any entry. */
  public static ThrowingFileSystemWrapper failingBeforeTheFirstEntry(
      FileSystem delegate, Supplier<RuntimeException> exceptionSupplier) {
    return new ThrowingFileSystemWrapper(delegate, exceptionSupplier, 0);
  }

  /**
   * Returns a wrapper that lists every entry and gives the attributes of each entry named {@code
   * fileName} to their first read only. Every later read of them fails with {@code failure}, as
   * when the entry changes after a walk lists it.
   */
  public static ThrowingFileSystemWrapper failingLaterAttributeReadsOf(
      FileSystem delegate, String fileName, Supplier<IOException> failure) {
    return new ThrowingFileSystemWrapper(
        delegate,
        () -> new IllegalStateException("The directory streams of this wrapper never fail"),
        Integer.MAX_VALUE,
        path ->
            Optional.of(path)
                .map(Path::getFileName)
                .filter(name -> name.toString().equals(fileName))
                .map(_ -> failure.get()));
  }

  /**
   * Returns a wrapper that never fails and runs {@code beforeAccessCheck} with each path before it
   * checks the path's access, as {@link java.nio.file.Files#exists} does.
   */
  public static ThrowingFileSystemWrapper runningBeforeEachAccessCheck(
      FileSystem delegate, Consumer<Path> beforeAccessCheck) {
    return neverFailing(delegate, new Hooks(beforeAccessCheck, _ -> {}));
  }

  /**
   * Returns a wrapper that never fails and runs {@code afterRead} each time it has read the
   * attributes of an entry named {@code fileName}, through a path or through a secure directory
   * stream.
   */
  public static ThrowingFileSystemWrapper runningAfterEachAttributeReadOf(
      FileSystem delegate, String fileName, Runnable afterRead) {
    return neverFailing(
        delegate,
        new Hooks(
            _ -> {},
            path ->
                Optional.ofNullable(path.getFileName())
                    .filter(name -> name.toString().equals(fileName))
                    .ifPresent(_ -> afterRead.run())));
  }

  private static ThrowingFileSystemWrapper neverFailing(FileSystem delegate, Hooks hooks) {
    return new ThrowingFileSystemWrapper(
        delegate,
        () -> new IllegalStateException("The directory streams of this wrapper never fail"),
        Integer.MAX_VALUE,
        _ -> Optional.empty(),
        hooks);
  }

  private ThrowingFileSystemWrapper(
      FileSystem delegate, Supplier<RuntimeException> exceptionSupplier, int entriesBeforeFailure) {
    this(delegate, exceptionSupplier, entriesBeforeFailure, _ -> Optional.empty());
  }

  private ThrowingFileSystemWrapper(
      FileSystem delegate,
      Supplier<RuntimeException> exceptionSupplier,
      int entriesBeforeFailure,
      Function<Path, Optional<IOException>> laterAttributeReadFailure) {
    this(
        delegate,
        exceptionSupplier,
        entriesBeforeFailure,
        laterAttributeReadFailure,
        new Hooks(_ -> {}, _ -> {}));
  }

  private ThrowingFileSystemWrapper(
      FileSystem delegate,
      Supplier<RuntimeException> exceptionSupplier,
      int entriesBeforeFailure,
      Function<Path, Optional<IOException>> laterAttributeReadFailure,
      Hooks hooks) {
    this.delegate = delegate;
    this.exceptionSupplier = exceptionSupplier;
    this.entriesBeforeFailure = entriesBeforeFailure;
    this.laterAttributeReadFailure = laterAttributeReadFailure;
    this.hooks = hooks;
    this.throwingProvider = new ThrowingFileSystemProvider(delegate.provider(), this);
  }

  // Each hook receives a path of the wrapped file system.
  private record Hooks(Consumer<Path> beforeAccessCheck, Consumer<Path> afterAttributeRead) {}

  private void throwIfALaterAttributeReadFails(Path path) throws IOException {
    if (pathsWithReadAttributes.add(path)) {
      return;
    }

    var failure = laterAttributeReadFailure.apply(path);
    if (failure.isPresent()) {
      throw failure.get();
    }
  }

  @Override
  public FileSystemProvider provider() {
    return throwingProvider;
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  @Override
  public boolean isOpen() {
    return delegate.isOpen();
  }

  @Override
  public boolean isReadOnly() {
    return delegate.isReadOnly();
  }

  @Override
  public String getSeparator() {
    return delegate.getSeparator();
  }

  @Override
  public Iterable<Path> getRootDirectories() {
    return delegate.getRootDirectories();
  }

  @Override
  public Iterable<FileStore> getFileStores() {
    return delegate.getFileStores();
  }

  @Override
  public Set<String> supportedFileAttributeViews() {
    return delegate.supportedFileAttributeViews();
  }

  @Override
  public Path getPath(String first, String... more) {
    return new WrappedPath(delegate.getPath(first, more), this);
  }

  @Override
  public PathMatcher getPathMatcher(String syntaxAndPattern) {
    return delegate.getPathMatcher(syntaxAndPattern);
  }

  @Override
  public UserPrincipalLookupService getUserPrincipalLookupService() {
    return delegate.getUserPrincipalLookupService();
  }

  @Override
  public WatchService newWatchService() throws IOException {
    return delegate.newWatchService();
  }

  private static class WrappedPath implements Path {

    private final Path delegate;
    private final ThrowingFileSystemWrapper fileSystem;

    WrappedPath(Path delegate, ThrowingFileSystemWrapper fileSystem) {
      this.delegate = delegate;
      this.fileSystem = fileSystem;
    }

    Path unwrap() {
      return delegate;
    }

    @Override
    public FileSystem getFileSystem() {
      return fileSystem;
    }

    @Override
    public boolean isAbsolute() {
      return delegate.isAbsolute();
    }

    @Override
    public Path getRoot() {
      var root = delegate.getRoot();
      return root != null ? new WrappedPath(root, fileSystem) : null;
    }

    @Override
    public Path getFileName() {
      var fileName = delegate.getFileName();
      return fileName != null ? new WrappedPath(fileName, fileSystem) : null;
    }

    @Override
    public Path getParent() {
      var parent = delegate.getParent();
      return parent != null ? new WrappedPath(parent, fileSystem) : null;
    }

    @Override
    public int getNameCount() {
      return delegate.getNameCount();
    }

    @Override
    public Path getName(int index) {
      return new WrappedPath(delegate.getName(index), fileSystem);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
      return new WrappedPath(delegate.subpath(beginIndex, endIndex), fileSystem);
    }

    @Override
    public boolean startsWith(Path other) {
      return delegate.startsWith(unwrapIfWrapped(other));
    }

    @Override
    public boolean endsWith(Path other) {
      return delegate.endsWith(unwrapIfWrapped(other));
    }

    @Override
    public Path normalize() {
      return new WrappedPath(delegate.normalize(), fileSystem);
    }

    @Override
    public Path resolve(Path other) {
      return new WrappedPath(delegate.resolve(unwrapIfWrapped(other)), fileSystem);
    }

    @Override
    public Path relativize(Path other) {
      return new WrappedPath(delegate.relativize(unwrapIfWrapped(other)), fileSystem);
    }

    @Override
    public URI toUri() {
      return delegate.toUri();
    }

    @Override
    public Path toAbsolutePath() {
      return new WrappedPath(delegate.toAbsolutePath(), fileSystem);
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
      return new WrappedPath(delegate.toRealPath(options), fileSystem);
    }

    @Override
    public File toFile() {
      return delegate.toFile();
    }

    @Override
    public WatchKey register(
        WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers)
        throws IOException {
      return delegate.register(watcher, events, modifiers);
    }

    @Override
    public int compareTo(Path other) {
      return delegate.compareTo(unwrapIfWrapped(other));
    }

    @Override
    public String toString() {
      return delegate.toString();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof WrappedPath wp) {
        return delegate.equals(wp.delegate);
      }
      return delegate.equals(obj);
    }

    @Override
    public int hashCode() {
      return delegate.hashCode();
    }

    private static Path unwrapIfWrapped(Path path) {
      return path instanceof WrappedPath wp ? wp.delegate : path;
    }
  }

  private static class ThrowingFileSystemProvider extends FileSystemProvider {

    private final FileSystemProvider delegate;
    private final ThrowingFileSystemWrapper fileSystem;

    ThrowingFileSystemProvider(FileSystemProvider delegate, ThrowingFileSystemWrapper fileSystem) {
      this.delegate = delegate;
      this.fileSystem = fileSystem;
    }

    private static Path unwrap(Path path) {
      return path instanceof WrappedPath wp ? wp.unwrap() : path;
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(
        Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
      var delegateStream = delegate.newDirectoryStream(unwrap(dir), filter);
      if (delegateStream instanceof SecureDirectoryStream<Path> secureStream) {
        return new ThrowingSecureDirectoryStream(secureStream, fileSystem);
      }

      return new ThrowingDirectoryStream(delegateStream, fileSystem);
    }

    @Override
    public FileChannel newFileChannel(
        Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {
      return delegate.newFileChannel(unwrap(path), options, attrs);
    }

    @Override
    public String getScheme() {
      return delegate.getScheme();
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
      return delegate.newFileSystem(uri, env);
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
      return fileSystem;
    }

    @Override
    public Path getPath(URI uri) {
      return new WrappedPath(delegate.getPath(uri), fileSystem);
    }

    @Override
    public SeekableByteChannel newByteChannel(
        Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {
      return delegate.newByteChannel(unwrap(path), options, attrs);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
      delegate.createDirectory(unwrap(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
      delegate.delete(unwrap(path));
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
      delegate.copy(unwrap(source), unwrap(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
      delegate.move(unwrap(source), unwrap(target), options);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
      return delegate.isSameFile(unwrap(path), unwrap(path2));
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
      return delegate.isHidden(unwrap(path));
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
      return delegate.getFileStore(unwrap(path));
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
      fileSystem.hooks.beforeAccessCheck().accept(unwrap(path));
      delegate.checkAccess(unwrap(path), modes);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(
        Path path, Class<V> type, LinkOption... options) {
      return delegate.getFileAttributeView(unwrap(path), type, options);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(
        Path path, Class<A> type, LinkOption... options) throws IOException {
      fileSystem.throwIfALaterAttributeReadFails(unwrap(path));
      var attributes = delegate.readAttributes(unwrap(path), type, options);
      fileSystem.hooks.afterAttributeRead().accept(unwrap(path));
      return attributes;
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
        throws IOException {
      return delegate.readAttributes(unwrap(path), attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
        throws IOException {
      delegate.setAttribute(unwrap(path), attribute, value, options);
    }
  }

  private static class ThrowingDirectoryStream implements DirectoryStream<Path> {

    private final DirectoryStream<Path> delegate;
    private final ThrowingFileSystemWrapper fileSystem;

    ThrowingDirectoryStream(DirectoryStream<Path> delegate, ThrowingFileSystemWrapper fileSystem) {
      this.delegate = delegate;
      this.fileSystem = fileSystem;
    }

    @Override
    public Iterator<Path> iterator() {
      return new ThrowingIterator(delegate.iterator(), fileSystem);
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  // Offered where the wrapped file system offers it, as by Jimfs and the JDK on Linux and macOS.
  private static final class ThrowingSecureDirectoryStream extends ThrowingDirectoryStream
      implements SecureDirectoryStream<Path> {

    private final SecureDirectoryStream<Path> delegate;
    private final ThrowingFileSystemWrapper fileSystem;

    ThrowingSecureDirectoryStream(
        SecureDirectoryStream<Path> delegate, ThrowingFileSystemWrapper fileSystem) {
      super(delegate, fileSystem);
      this.delegate = delegate;
      this.fileSystem = fileSystem;
    }

    @Override
    public SecureDirectoryStream<Path> newDirectoryStream(Path path, LinkOption... options)
        throws IOException {
      return new ThrowingSecureDirectoryStream(
          delegate.newDirectoryStream(ThrowingFileSystemProvider.unwrap(path), options),
          fileSystem);
    }

    @Override
    public SeekableByteChannel newByteChannel(
        Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {
      return delegate.newByteChannel(ThrowingFileSystemProvider.unwrap(path), options, attrs);
    }

    @Override
    public void deleteFile(Path path) throws IOException {
      delegate.deleteFile(ThrowingFileSystemProvider.unwrap(path));
    }

    @Override
    public void deleteDirectory(Path path) throws IOException {
      delegate.deleteDirectory(ThrowingFileSystemProvider.unwrap(path));
    }

    @Override
    public void move(Path srcpath, SecureDirectoryStream<Path> targetdir, Path targetpath)
        throws IOException {
      var target =
          targetdir instanceof ThrowingSecureDirectoryStream wrapped ? wrapped.delegate : targetdir;
      delegate.move(
          ThrowingFileSystemProvider.unwrap(srcpath),
          target,
          ThrowingFileSystemProvider.unwrap(targetpath));
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Class<V> type) {
      return delegate.getFileAttributeView(type);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(
        Path path, Class<V> type, LinkOption... options) {
      var unwrapped = ThrowingFileSystemProvider.unwrap(path);
      var view = delegate.getFileAttributeView(unwrapped, type, options);
      if (type == BasicFileAttributeView.class) {
        return type.cast(
            new HookedBasicFileAttributeView((BasicFileAttributeView) view, unwrapped, fileSystem));
      }

      return view;
    }
  }

  private record HookedBasicFileAttributeView(
      BasicFileAttributeView delegate, Path path, ThrowingFileSystemWrapper fileSystem)
      implements BasicFileAttributeView {

    @Override
    public String name() {
      return delegate.name();
    }

    @Override
    public BasicFileAttributes readAttributes() throws IOException {
      var attributes = delegate.readAttributes();
      fileSystem.hooks.afterAttributeRead().accept(path);
      return attributes;
    }

    @Override
    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime)
        throws IOException {
      delegate.setTimes(lastModifiedTime, lastAccessTime, createTime);
    }
  }

  private static class ThrowingIterator implements Iterator<Path> {

    private final Iterator<Path> delegate;
    private final ThrowingFileSystemWrapper fileSystem;
    private int returnedEntries = 0;

    ThrowingIterator(Iterator<Path> delegate, ThrowingFileSystemWrapper fileSystem) {
      this.delegate = delegate;
      this.fileSystem = fileSystem;
    }

    @Override
    public boolean hasNext() {
      if (returnedEntries >= fileSystem.entriesBeforeFailure) {
        throw fileSystem.exceptionSupplier.get();
      }
      return delegate.hasNext();
    }

    @Override
    public Path next() {
      var result = delegate.next();
      returnedEntries++;
      return new WrappedPath(result, fileSystem);
    }
  }
}
