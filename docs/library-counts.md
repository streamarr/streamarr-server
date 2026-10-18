# Library counts

`Library.itemCount` is the number of top-level movies or series in the library at
its last recount. It is independent of the selected filter, sort and page. Seasons,
episodes and extra file versions do not increase it. An empty library returns zero.
The total equals the sum of `alphabetIndex.count`, including `HASH` for titles
whose sort title does not start with an ASCII letter.

The upgrade migration rebuilds existing alphabet buckets from top-level movies and series,
removing legacy season/episode counts and stale buckets.

The total uses the existing stored alphabet counts. A file-watcher item-processing
recount replaces those counts when no library operation is running. During a scan
or refresh, the previous counts remain visible until the operation's finishing
recount commits, including a failed operation's finishing steps. Counts are not
scan progress. Changing operation cancellation does not introduce a separate count
lifecycle: its finishing path must retain the same recount.

Both `library(id: ...)` and `libraries` expose the total. Total reads for multiple
libraries are batched. Authorization is the same as the corresponding queries:
a selected Profile is required. The current library queries expose every library
to a selected Profile; this field does not introduce an administrative restriction
or bypass a narrower query policy.

`LibraryCountsChangedEvent` identifies a library whose stored counts were replaced.
Consumers observe this application event after commit, using an
`AFTER_COMMIT` transactional event listener. When the recount owns no enclosing
transaction, the repository's transaction has committed before publication.
Rollback publishes no committed change. Consumers read current authoritative
values, rather than deriving a whole-library total from their loaded items.
