package com.streamarr.server.services.library;

import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A stored scan or refresh of one library. It exists from the transaction that starts the work
 * until the transaction that ends the work.
 *
 * @param imageRefreshMode the refresh's image refresh mode; a scan has none
 * @param statusBeforeOperation the idle status the library showed when the operation started
 * @param createdBy the account, or the System actor, that requested the operation
 * @param createdOn when the transaction that started the operation began
 */
@Builder(toBuilder = true)
public record LibraryOperation(
    @NonNull UUID id,
    @NonNull UUID libraryId,
    @NonNull LibraryOperationKind kind,
    @NonNull Optional<ImageRefreshMode> imageRefreshMode,
    @NonNull LibraryStatus statusBeforeOperation,
    @NonNull UUID createdBy,
    @NonNull Instant createdOn) {}
