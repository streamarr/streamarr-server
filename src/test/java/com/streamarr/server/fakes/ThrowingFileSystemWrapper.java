package com.streamarr.server.fakes;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Set;

/**
 * A FileSystem wrapper that throws UncheckedIOException during Files.walk() iteration. Used to test
 * error handling when file system traversal fails mid-stream.
 *
 * <p>Jimfs cannot simulate permission errors, so this wrapper intercepts directory stream iteration
 * and throws UncheckedIOException after returning the first element.
 */
public class ThrowingFileSystemWrapper extends FileSystem {

  private final FileSystem delegate;
  private final ThrowingFileSystemProvider throwingProvider;

  public ThrowingFileSystemWrapper(FileSystem delegate) {
    this.delegate = delegate;
    this.throwingProvider = new ThrowingFileSystemProvider(delegate.provider(), this);
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
      return new ThrowingDirectoryStream(delegateStream, fileSystem);
    }

    @Override
    public String getScheme() {
      return delegate.getScheme();
    }

    @Override
    public FileSystem newFileSystem(java.net.URI uri, java.util.Map<String, ?> env)
        throws IOException {
      return delegate.newFileSystem(uri, env);
    }

    @Override
    public FileSystem getFileSystem(java.net.URI uri) {
      return fileSystem;
    }

    @Override
    public Path getPath(java.net.URI uri) {
      return new WrappedPath(delegate.getPath(uri), fileSystem);
    }

    @Override
    public java.nio.channels.SeekableByteChannel newByteChannel(
        Path path,
        Set<? extends java.nio.file.OpenOption> options,
        java.nio.file.attribute.FileAttribute<?>... attrs)
        throws IOException {
      return delegate.newByteChannel(unwrap(path), options, attrs);
    }

    @Override
    public void createDirectory(Path dir, java.nio.file.attribute.FileAttribute<?>... attrs)
        throws IOException {
      delegate.createDirectory(unwrap(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
      delegate.delete(unwrap(path));
    }

    @Override
    public void copy(Path source, Path target, java.nio.file.CopyOption... options)
        throws IOException {
      delegate.copy(unwrap(source), unwrap(target), options);
    }

    @Override
    public void move(Path source, Path target, java.nio.file.CopyOption... options)
        throws IOException {
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
    public void checkAccess(Path path, java.nio.file.AccessMode... modes) throws IOException {
      delegate.checkAccess(unwrap(path), modes);
    }

    @Override
    public <V extends java.nio.file.attribute.FileAttributeView> V getFileAttributeView(
        Path path, Class<V> type, java.nio.file.LinkOption... options) {
      return delegate.getFileAttributeView(unwrap(path), type, options);
    }

    @Override
    public <A extends java.nio.file.attribute.BasicFileAttributes> A readAttributes(
        Path path, Class<A> type, java.nio.file.LinkOption... options) throws IOException {
      return delegate.readAttributes(unwrap(path), type, options);
    }

    @Override
    public java.util.Map<String, Object> readAttributes(
        Path path, String attributes, java.nio.file.LinkOption... options) throws IOException {
      return delegate.readAttributes(unwrap(path), attributes, options);
    }

    @Override
    public void setAttribute(
        Path path, String attribute, Object value, java.nio.file.LinkOption... options)
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

  private static class ThrowingIterator implements Iterator<Path> {

    private final Iterator<Path> delegate;
    private final ThrowingFileSystemWrapper fileSystem;
    private boolean firstReturned = false;

    ThrowingIterator(Iterator<Path> delegate, ThrowingFileSystemWrapper fileSystem) {
      this.delegate = delegate;
      this.fileSystem = fileSystem;
    }

    @Override
    public boolean hasNext() {
      if (firstReturned) {
        throw new UncheckedIOException(
            new IOException("Simulated permission denied during directory traversal"));
      }
      return delegate.hasNext();
    }

    @Override
    public Path next() {
      var result = delegate.next();
      firstReturned = true;
      return new WrappedPath(result, fileSystem);
    }
  }
}
