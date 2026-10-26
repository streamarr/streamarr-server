package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.graphql.mutation.InputPath;
import com.streamarr.server.services.library.AddLibraryRejection;
import com.streamarr.server.services.library.LibraryPathOverlap;

/** The exhaustive mapping from service rejection to schema error type. */
public final class AddLibraryErrors {

  private static final String FILEPATH = "filepath";
  private static final String CHOOSE_ANOTHER_FOLDER =
      "Choose a folder that no other library covers.";

  private AddLibraryErrors() {}

  public static AddLibraryError toError(AddLibraryRejection rejection) {
    return switch (rejection) {
      case AddLibraryRejection.NameRequired _ ->
          new AddLibraryError.LibraryNameRequiredError(
              "Enter a library name.", InputPath.of("name"));
      case AddLibraryRejection.PathRequired _ ->
          new AddLibraryError.LibraryPathRequiredError(
              "Enter the folder to scan.", InputPath.of(FILEPATH));
      case AddLibraryRejection.PathNotFound _ ->
          new AddLibraryError.LibraryPathNotFoundError(
              "That folder does not exist on the server.", InputPath.of(FILEPATH));
      case AddLibraryRejection.PathNotDirectory _ ->
          new AddLibraryError.LibraryPathNotDirectoryError(
              "That path is a file, not a folder.", InputPath.of(FILEPATH));
      case AddLibraryRejection.PathNotReadable _ ->
          new AddLibraryError.LibraryPathNotReadableError(
              "The server cannot read that folder.", InputPath.of(FILEPATH));
      case AddLibraryRejection.PathAlreadyRegistered(var library, var path) ->
          new AddLibraryError.LibraryPathAlreadyRegisteredError(
              "The library \"%s\" already uses this folder (%s). %s"
                  .formatted(library.getName(), path, CHOOSE_ANOTHER_FOLDER),
              InputPath.of(FILEPATH),
              library,
              path.toString());
      case AddLibraryRejection.PathOverlap(var relation, var library, var path) ->
          new AddLibraryError.LibraryPathOverlapError(
              "This folder %s the folder of the library \"%s\" (%s). %s"
                  .formatted(overlapVerb(relation), library.getName(), path, CHOOSE_ANOTHER_FOLDER),
              InputPath.of(FILEPATH),
              library,
              path.toString(),
              relation);
    };
  }

  private static String overlapVerb(LibraryPathOverlap relation) {
    return switch (relation) {
      case INSIDE -> "is inside";
      case CONTAINS -> "contains";
    };
  }
}
