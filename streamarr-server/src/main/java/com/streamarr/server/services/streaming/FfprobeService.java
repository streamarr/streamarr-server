package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.MediaProbe;
import java.nio.file.Path;

public interface FfprobeService {

  MediaProbe probe(Path filepath);
}
