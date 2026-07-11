package com.streamarr.server.services.metadata;

import java.io.IOException;

public interface TmdbImageDownloader {

  byte[] downloadImage(String pathFragment) throws IOException, InterruptedException;
}
