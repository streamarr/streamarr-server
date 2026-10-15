package com.streamarr.server.exceptions;

/** The server settings row that the server_settings migration seeds no longer exists. */
public class ServerSettingsMissingException extends RuntimeException {

  public ServerSettingsMissingException() {
    super("The server settings row is missing");
  }
}
