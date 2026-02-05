package com.streamarr.server.fakes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
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
    return delegate.getPath(first, more);
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

  private static class ThrowingFileSystemProvider extends FileSystemProvider {

    private final FileSystemProvider delegate;
    private final ThrowingFileSystemWrapper fileSystem;

    ThrowingFileSystemProvider(FileSystemProvider delegate, ThrowingFileSystemWrapper fileSystem) {
      this.delegate = delegate;
      this.fileSystem = fileSystem;
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(
        Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
      var delegateStream = delegate.newDirectoryStream(dir, filter);
      return new ThrowingDirectoryStream(delegateStream);
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
      return delegate.getPath(uri);
    }

    @Override
    public java.nio.channels.SeekableByteChannel newByteChannel(
        Path path,
        Set<? extends java.nio.file.OpenOption> options,
        java.nio.file.attribute.FileAttribute<?>... attrs)
        throws IOException {
      return delegate.newByteChannel(path, options, attrs);
    }

    @Override
    public void createDirectory(Path dir, java.nio.file.attribute.FileAttribute<?>... attrs)
        throws IOException {
      delegate.createDirectory(dir, attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
      delegate.delete(path);
    }

    @Override
    public void copy(Path source, Path target, java.nio.file.CopyOption... options)
        throws IOException {
      delegate.copy(source, target, options);
    }

    @Override
    public void move(Path source, Path target, java.nio.file.CopyOption... options)
        throws IOException {
      delegate.move(source, target, options);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
      return delegate.isSameFile(path, path2);
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
      return delegate.isHidden(path);
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
      return delegate.getFileStore(path);
    }

    @Override
    public void checkAccess(Path path, java.nio.file.AccessMode... modes) throws IOException {
      delegate.checkAccess(path, modes);
    }

    @Override
    public <V extends java.nio.file.attribute.FileAttributeView> V getFileAttributeView(
        Path path, Class<V> type, java.nio.file.LinkOption... options) {
      return delegate.getFileAttributeView(path, type, options);
    }

    @Override
    public <A extends java.nio.file.attribute.BasicFileAttributes> A readAttributes(
        Path path, Class<A> type, java.nio.file.LinkOption... options) throws IOException {
      return delegate.readAttributes(path, type, options);
    }

    @Override
    public java.util.Map<String, Object> readAttributes(
        Path path, String attributes, java.nio.file.LinkOption... options) throws IOException {
      return delegate.readAttributes(path, attributes, options);
    }

    @Override
    public void setAttribute(
        Path path, String attribute, Object value, java.nio.file.LinkOption... options)
        throws IOException {
      delegate.setAttribute(path, attribute, value, options);
    }
  }

  private static class ThrowingDirectoryStream implements DirectoryStream<Path> {

    private final DirectoryStream<Path> delegate;

    ThrowingDirectoryStream(DirectoryStream<Path> delegate) {
      this.delegate = delegate;
    }

    @Override
    public Iterator<Path> iterator() {
      return new ThrowingIterator(delegate.iterator());
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  private static class ThrowingIterator implements Iterator<Path> {

    private final Iterator<Path> delegate;
    private boolean firstReturned = false;

    ThrowingIterator(Iterator<Path> delegate) {
      this.delegate = delegate;
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
      return result;
    }
  }
}
