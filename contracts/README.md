# Live-list conformance

`live-lists.json` contains shared examples for web and Apple clients, pinned and vendored with the server schema. Client tests apply these inputs through their real list/cache adapters and compare against the literal expected IDs. Neither client decodes or derives the supplied order keys.

Continue Watching starts from the first 12 or 20 entries for the client's shelf cap. Each step follows the preceding step. Every event fixture has positive progress, including nonmembers: membership comes exclusively from `matchesFilter`. Removals do not backfill; an eligible existing item can move to the tail, and a new item can occupy a free slot.

Library scenarios load `initialLoaded` entries from `initial`, in `pageSize` pages, apply `change`, recover against `recovered`, then apply `followUp`. The ordered arrays represent the server's results; cursors in the test adapters identify these fixture entries. `startLetter` is an initial seek anchor, and recovery must preserve the coverage already established by the window. The scenarios cover a move between pages and an insertion before an alphabet anchor when the window has already reached the true start.

Update these fixtures deliberately when an accepted contract changes, then update both client pins and run both consumers. A fixture change does not itself authorize changing the contract.
