package com.streamarr.server.exceptions;

import com.streamarr.server.domain.ExternalAgentStrategy;

public class MissingMetadataProviderException extends RuntimeException {

  public MissingMetadataProviderException(ExternalAgentStrategy strategy) {
    super("No metadata provider configured for strategy " + strategy);
  }
}
