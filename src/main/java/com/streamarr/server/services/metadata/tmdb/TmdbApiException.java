package com.streamarr.server.services.metadata.tmdb;

import java.io.IOException;
import lombok.Getter;

@Getter
public class TmdbApiException extends IOException {

  private final int statusCode;

  public TmdbApiException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }
}
