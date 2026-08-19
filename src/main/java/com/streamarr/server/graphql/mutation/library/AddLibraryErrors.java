package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.library.AddLibraryRejection;

/** The exhaustive mapping from service rejection to schema error type. */
public final class AddLibraryErrors {

  private AddLibraryErrors() {}

  public static AddLibraryError toError(AddLibraryRejection rejection) {
    return switch (rejection) {
      case AddLibraryRejection.NameRequired _ ->
          new AddLibraryError.LibraryNameRequiredError(
              "Enter a library name.", InputPath.of("name"));
      case AddLibraryRejection.PathRequired _ ->
          new AddLibraryError.LibraryPathRequiredError(
              "Enter the folder to scan.", InputPath.of("filepath"));
      case AddLibraryRejection.PathNotFound _ ->
          new AddLibraryError.LibraryPathNotFoundError(
              "That folder does not exist on the server.", InputPath.of("filepath"));
      case AddLibraryRejection.PathNotDirectory _ ->
          new AddLibraryError.LibraryPathNotDirectoryError(
              "That path is a file, not a folder.", InputPath.of("filepath"));
      case AddLibraryRejection.PathNotReadable _ ->
          new AddLibraryError.LibraryPathNotReadableError(
              "The server cannot read that folder.", InputPath.of("filepath"));
      case AddLibraryRejection.PathAlreadyRegistered _ ->
          new AddLibraryError.LibraryPathAlreadyRegisteredError(
              "A library already uses that folder.", InputPath.of("filepath"));
    };
  }
}
