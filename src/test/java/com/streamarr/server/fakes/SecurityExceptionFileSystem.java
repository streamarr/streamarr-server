package com.streamarr.server.fakes;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Set;

/**
 * A FileSystem wrapper that throws SecurityException when checking path access. Used to test error
 * handling when SecurityManager denies access to paths.
 */
public class SecurityExceptionFileSystem extends FileSystem {

  private final FileSystem delegate;
  private final SecurityExceptionProvider provider;

  public SecurityExceptionFileSystem(FileSystem delegate) {
    this.delegate = delegate;
    this.provider = new SecurityExceptionProvider(delegate.provider());
  }

  @Override
  public FileSystemProvider provider() {
    return provider;
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

  private static class SecurityExceptionProvider extends FileSystemProvider {

    private final FileSystemProvider delegate;

    SecurityExceptionProvider(FileSystemProvider delegate) {
      this.delegate = delegate;
    }

    @Override
    public void checkAccess(Path path, java.nio.file.AccessMode... modes) {
      throw new SecurityException("Simulated security manager denial for path: " + path);
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
      return delegate.getFileSystem(uri);
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
    public java.nio.file.DirectoryStream<Path> newDirectoryStream(
        Path dir, java.nio.file.DirectoryStream.Filter<? super Path> filter) throws IOException {
      return delegate.newDirectoryStream(dir, filter);
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
}
