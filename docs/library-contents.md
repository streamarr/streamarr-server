# Live library contents

Library queries supply initial state. Four independent operations on the shared
`graphql-transport-ws` connection keep that state current:

| Subscription | Payload |
| --- | --- |
| `libraryItemAdded(libraryId)` | `LibraryItemChange`: library ID, item ID, kind, selectable `Movie` or `Series`, and `edge(sort, filter)` |
| `libraryItemUpdated(libraryId)` | The same payload, including changes to a series' seasons or episodes |
| `libraryItemRemoved(libraryId)` | Library ID, item ID and kind, which remain meaningful after deletion |
| `libraryCountsChanged(libraryId)` | A selectable `Library`, including authoritative `itemCount` and `alphabetIndex` |

Queries, including recovery queries, authorize the selected Profile from the signed token.
Subscriptions check live Profile activity authority once when they open; delivery remains
bounded by the opening token's expiry (ADRs 0016, 0025 and 0042).
They do not require ServerAdmin authority. Library-administration status subscriptions
remain separate. The current catalog queries expose all libraries to a selected
Profile; these operations use that same visibility. A future restriction on readable
items must be shared by queries, item lookup, removal receipts and aggregate reads,
not applied only to the subscription's entity field. When readable scope is lost,
clients must discard that scope's cached entities and connections rather than infer
that a hidden item was deleted.

A watch-state change updates library subscribers belonging to that Profile only.
The selected Profile comes from the authenticated connection, never an input argument.
Metadata and artwork changes are delivered to every subscribed Profile in that library;
selected watch-state fields still resolve for each subscriber's own Profile.

## Placement and pagination

Select `orderKey` on ordinary `MediaEdge` results. Select `edge` with the **same sort
and filter** on addition and update payloads:

```graphql
subscription LibraryAdditions($id: ID!, $sort: MediaSort, $filter: MediaFilter) {
  libraryItemAdded(libraryId: $id) {
    libraryId
    itemId
    kind
    item {
      __typename
      ... on Movie { id title }
      ... on Series { id title }
    }
    edge(sort: $sort, filter: $filter) {
      cursor
      orderKey
      node {
        ... on Movie { id title }
        ... on Series { id title }
      }
    }
  }
}
```

Use the same selection on `libraryItemUpdated`. Clients choose any additional fields
needed by their screens, including images, credits, genres, watch state and nested
seasons/episodes. The server evaluates membership with the query's filter predicates;
clients do not reconstruct filters from a partially selected entity.

- A present `edge` is the same `MediaEdge` shape as `Library.items`, with its cursor,
  order key and selectable node. Query pages and changes share edge construction.
- An explicitly resolved `edge: null` means the item is not in this view, including
  an item deleted before delivery. Remove an existing edge without backfilling only
  if no error response path starts at this change's path (aliases and list indices
  included). An edge with `node: null` never means removal. Preserve an errored
  change's entity and membership until shared query recovery succeeds.
- Leaving a filtered view does not evict the entity. Only deletion receipts do.
- `item` may be null when a later commit has already removed it.
- Compare keys as **ascending ASCII strings**, even for a descending sort. Do not use
  locale-aware comparison. Keys include the ID tie-breaker and encode null placement.
  Treat their representation as opaque; only compare keys from the same view.
- Pass the cursor unchanged to `before`/`after`. A key is not a cursor. Never synthesize
  a cursor or decode one to recreate sorting logic.
- TITLE ordering uses PostgreSQL `lower(title_sort COLLATE "C")`. Its seek index orders
  a null flag, the normalized title with null coalesced to an empty string, and the ID.
  Comparing that whole tuple keeps nullable cursor seeks within the index range instead
  of scanning earlier titles. The database performs case normalization for keys too,
  so client locale and Unicode casing cannot disagree with query ordering. The server
  writes `title_sort` from the display title, which itself never changes: Latin
  diacritics, ligatures and full-width Latin letters fold to their base letters, so
  `Élite` sorts and counts as `Elite`; leading `{`, `|`, `}` and `~` are removed; and a
  leading article moves to the end when it is written without diacritics, so `À bout de
  souffle` stays under A. Text in other scripts is left exactly as written
  and sorts above `z`. RELEASE_DATE,
  RUNTIME and LAST_WATCHED keep nulls last in either direction. TITLE and ADDED retain
  SQL's ascending-null-last and descending-null-first ordering; the added time is never
  null. Backward seeks reverse the physical null order too.
- TITLE `startLetter` remains a seek anchor under ADR 0023. It is not a membership
  restriction for the landing page, event placement or cursor pages. Every landing page
  is one contiguous run of the ordering. Descending HASH lands on the titles that sort
  below `a`, such as digits; titles that sort above `z` stay at the top of the
  ordering and are reached by paging backward. A view with no title below `a` lands
  at the top instead. Under other sorts the letter remains a filter on every page.

Normalize entity fields, then reconcile the selected connection by item ID. An in-range
addition grows 48 items to 49. A matching item outside a partial window waits for ordinary
pagination; a window loaded to an end can extend at that end. An update can move an item
or remove it from the window. A removal shrinks 48 to 47 without backfilling. Reapplying
an event must not duplicate an edge.

Keep the loaded window's bounds separately from its changing item count. Preserve usable
server pagination anchors if removal empties a partial window; a cursor represents a
position even when its original item has been removed. Page merges deduplicate IDs.
This retained-position behavior is Streamarr's keyset contract: the
[Relay reference algorithm](https://relay.dev/graphql/connections.htm#sec-Pagination-algorithm)
only applies a boundary when its edge still exists. Keep retained navigation anchors separate
from a live connection's `pageInfo` cursors, which describe its current first and last edges.
Whole-library counts cannot be derived from a loaded window. Apply the separate count
payload even when a change affects a title outside that window. See
[library-counts.md](library-counts.md) for the recount schedule and top-level semantics.

## Publication and delivery

Movie/series creation, metadata refresh, file association/removal and saved title artwork
publish changes. Season/episode changes identify their parent series. The application
events carry IDs, not detached entities. Parent IDs for nested changes are resolved in
the writer's transaction; WebSocket delivery reads selectable fields on the delivery
thread after commit. A rollback delivers nothing.

There is no startup snapshot, readiness handshake, revision or global ordering guarantee.
The query-to-registration gap is accepted. Each operation serializes its delivery, but
concurrent transactions and different operations do not establish a global order.
Selectable fields are current reads and may already reflect a later commit. Delivery is
not exactly once and does not promise every intermediate state. A late query may overwrite
newer cached fields until another event or revalidation.

Every operation uses the shared bounded delivery buffer. Publication does not wait for a
slow client. Overflow fails the affected operation under the shared transport contract;
recovery recreates desired recoverable operations and rereads relevant queries. There is
no polling, timer-based coalescing, or extra query after an ordinary item/count event.

Removing a library completes all four operations after commit. Registration checks for
existence after installing the listener, so deletion racing with registration cannot
leave a listener behind. An already absent library completes immediately. Clients treat
this completion as terminal, mark the scope unavailable, release its observers, and do
not restore its operations on reconnect.

## Client lifetime and recovery

Web and Apple consumers retain the library subscription and its query/cache window across
library → movie/series/season → Back. The library query stays in the desired recovery set
while detail is open. Leave the scope when navigating elsewhere or switching libraries,
and discard private state on identity changes. Keep hidden-tab subscriptions active,
subject to browser/platform suspension. On reconnect, recoverable operation failure,
foreground or online return, silently revalidate desired queries through the shared
connection/recovery mechanism.

The explicit contents operations coexist with `watchStateChanged`. The latter can update
normalized watch fields and Continue Watching; a connection using `LibraryItemChange.edge`
must have one owner for library membership so the two consumers cannot invent competing
edges or cursors.

Single-title refresh and rematch (#429 and #430) own their future publish points. When
those workflows add observable status/failure fields to `Movie` or `Series`, clients can
select them on this same `item` payload. Those workflows must publish after committing
state transitions, including failure. Mutation acceptance alone does not mean that
asynchronous work finished.
