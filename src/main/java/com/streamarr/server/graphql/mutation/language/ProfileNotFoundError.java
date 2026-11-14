package com.streamarr.server.graphql.mutation.language;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

/**
 * The Profile does not exist or is hidden from the caller. Each union's members share its package,
 * and DGS resolves the schema type by the record name.
 */
public record ProfileNotFoundError(String message, List<String> inputPath)
    implements SetProfileLanguagePreferencesError, InputMutationError {}
