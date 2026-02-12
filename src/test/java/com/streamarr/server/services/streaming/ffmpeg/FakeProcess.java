package com.streamarr.server.services.streaming.ffmpeg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

class FakeProcess extends Process {

  private final InputStream inputStream;
  private final int exitCode;

  FakeProcess(String stdout, int exitCode) {
    this.inputStream = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
    this.exitCode = exitCode;
  }

  @Override
  public OutputStream getOutputStream() {
    return new ByteArrayOutputStream();
  }

  @Override
  public InputStream getInputStream() {
    return inputStream;
  }

  @Override
  public InputStream getErrorStream() {
    return new ByteArrayInputStream(new byte[0]);
  }

  @Override
  public int waitFor() {
    return exitCode;
  }

  @Override
  public int exitValue() {
    return exitCode;
  }

  @Override
  public void destroy() {
    // no-op for test fake
  }
}
