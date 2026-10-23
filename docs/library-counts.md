# Library counts

`Library.itemCount` is the number of committed top-level movies or series in the
library. It is independent of the selected filter, sort and page. Seasons,
episodes and extra file versions do not increase it. An empty library returns zero.
The total equals the sum of `alphabetIndex.count`, including `HASH` for titles
whose sort title does not start with an ASCII letter. Sort titles fold Latin
diacritics, so `Élite` counts under `E`; titles in other scripts count under `HASH`.

The upgrade migrations rebuild existing alphabet buckets from top-level movies and series,
removing legacy season/episode counts and stale buckets. A later upgrade re-encodes stored
sort titles from their display titles before rebuilding the buckets again. V105 rebuilds the
buckets once more and installs the triggers that keep them current.

Database triggers change the letter index in the transaction that adds, removes or
renames a top-level item, whichever path writes it: a scan, a refresh, the file watcher,
a missing-file removal or a cascade. The counts therefore grow while a scan runs, and a
stopped or failed operation leaves them correct without a recount. No library operation
recalculates them. Counts are not scan progress. The trigger's letter rule matches the
start-letter seek in `JooqQueryHelper.alphabetLetterField`; `LibraryAlphabetIT` holds both
to the same titles.

Both `library(id: ...)` and `libraries` expose the total. Total reads for multiple
libraries are batched. Authorization is the same as the corresponding queries:
a selected Profile is required. The current library queries expose every library
to a selected Profile; this field does not introduce an administrative restriction
or bypass a narrower query policy.

`LibraryCountsChangedEvent` identifies a library whose committed counts changed.
`LibraryCountsChangedPublisher` publishes it after the movie or series events of a
committed transaction, at most once every two seconds per library: the first change
after a quiet interval publishes at once, and the changes within an interval publish
once when it ends. A rolled-back write publishes nothing. Watch-state changes of one
Profile publish nothing, because they change no count. Consumers read current
authoritative values when the event reaches them, rather than deriving a
whole-library total from their loaded items.
