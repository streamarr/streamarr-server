package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.support.NonUtf8LocaleProbeSupport.firstRegularFileUnder;
import static com.streamarr.server.support.NonUtf8LocaleProbeSupport.report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Reports the source key {@link RemoteMediaSourceMapper} derives for a file whose bytes on disk are
 * UTF-8, and the key {@link Path} text would give.
 *
 * <p>Runs inside the container started by {@code NonUtf8LocaleFilenameIT} with only the compiled
 * classes and the protobuf jars on the classpath.
 */
public final class NonUtf8LocaleSourceKeyProbe {

  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  private NonUtf8LocaleSourceKeyProbe() {}

  public static void main(String[] args) throws IOException {
    var root = Path.of(args[0]);
    var file = firstRegularFileUnder(root);
    var mapper = new RemoteMediaSourceMapper(SOURCE_NAMESPACE_ID, root);

    report("path.relativeKey", root.relativize(file).toString());
    report("mapper.relativeKey", mapper.map(file).getRelativeKey());
  }
}
