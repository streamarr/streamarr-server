package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

/** The {@code AddLibraryError} union; record names are the schema type names DGS resolves by. */
public sealed interface AddLibraryError extends InputMutationError {

  record LibraryNameRequiredError(String message, List<String> inputPath)
      implements AddLibraryError {}

  record LibraryPathRequiredError(String message, List<String> inputPath)
      implements AddLibraryError {}

  record LibraryPathNotFoundError(String message, List<String> inputPath)
      implements AddLibraryError {}

  record LibraryPathNotDirectoryError(String message, List<String> inputPath)
      implements AddLibraryError {}

  record LibraryPathNotReadableError(String message, List<String> inputPath)
      implements AddLibraryError {}

  record LibraryPathAlreadyRegisteredError(String message, List<String> inputPath)
      implements AddLibraryError {}
}
