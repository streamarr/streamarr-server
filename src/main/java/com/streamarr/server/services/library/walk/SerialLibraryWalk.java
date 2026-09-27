package com.streamarr.server.services.library.walk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The scan's original walk: {@code Files.walk} on the scan thread. */
@Component
@ConditionalOnProperty(name = "poc.walk", havingValue = "serial", matchIfMissing = true)
public class SerialLibraryWalk implements LibraryWalk {

  @Override
  public String name() {
    return "serial";
  }

  @Override
  public Stream<Path> walk(Path root) throws IOException {
    return Files.walk(root);
  }
}
