# Live-list conformance

`live-lists.json` contains shared examples for web and Apple clients, pinned and vendored with the server schema. Client tests apply these inputs through their real list/cache adapters and compare against the literal expected IDs. Neither client decodes or derives the supplied order keys.

Continue Watching starts from the first 12 or 20 entries for the client's shelf cap. Each step follows the preceding step. Every event fixture has positive progress, including nonmembers: membership comes exclusively from `change.edge`. A null edge is an explicit departure; a non-null edge carries its opaque order key and node identity. Removals do not backfill; an eligible existing item can move to the tail, and a new item can occupy a free slot.

Library scenarios load `initialLoaded` entries from `initial`, in `pageSize` pages, apply `change`, recover against `recovered`, then apply `followUp`. The ordered arrays represent the server's results; cursors in the test adapters identify these fixture entries. `startLetter` is an initial seek anchor, and recovery must preserve the coverage already established by the window. The scenarios cover a move between pages and an insertion before an alphabet anchor when the window has already reached the true start.

`LiveListContractIT` saves each Library scenario's titles and requires the server's TITLE order to match the listed order, so these orderings stay the server's real results.

Update these fixtures deliberately when an accepted contract changes, then update both client pins and run both consumers. A fixture change does not itself authorize changing the contract.

`edgeCases` are independent subscription scenarios, each starting with movies `a` and `b` in that order. `changes` is the delivered Continue Watching list; adapters add only selection fields (typename, cursor, card data), preserving null edges and null nodes. `errorPaths` are GraphQL response paths, including list indices and ancestor paths. Send a usable `movie-1` watch-state update alongside each scenario and a failed second entity when its error path names `items[1]`. Assert both that independent update and the literal expected shelf IDs before recovery completes. Both clients execute these cases through their public subscription path and real normalized cache.

`edgeCases` cover only the Continue Watching change list. Library changes follow the same null and error rule, stated in the Library SDL, and each client's own Library tests cover it: an explicit null edge removes membership, while a failed edge or a non-null edge with a null node preserves it.
