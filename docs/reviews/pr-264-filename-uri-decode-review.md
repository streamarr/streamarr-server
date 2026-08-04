# PR #264 combined review: derive persisted filename from the filepath URI

Date: 2026-08-04

PR: [#264](https://github.com/streamarr/streamarr-server/pull/264)

Base: `1a10a8bb775b1bfb70cf236600a48707ee32b3f8` (`main`)

Reviewed head: `08978c0ca13ae7dd6b32c42164c2445e66873d9c`

## Verdict

**Verdict for the reviewed head: request changes.** The core fix is sound: deriving display text from the percent-encoded filepath
URI avoids the locale-dependent `Path.toString()` corruption, and the Linux container integration
test genuinely reproduces that bug. The PR is not ready to merge because existing corrupt rows do
not heal, movie enrichment can continue searching with the corrupt filename, and the required
Sonar reliability gate currently fails.

The combined review also found two smaller production defects, a mutation-surviving test gap, and
several diagnostics/documentation issues. One suspected decoding defect (`%`, `+`, and `#` in a
codec-generated URI) was disproved by an executable round-trip test.

**TDD remediation status: complete in this worktree.** Every applicable confirmed finding now has
permanent regression coverage and an implementation. The broad invalid-library-path locale hint
remains rejected as a false positive, and the optional `FilepathUri` value object remains rejected
because it would be a shallow wrapper over the already-cohesive `FilepathCodec`. The local changes
have not been committed or pushed.

## TDD remediation record

Each behavioral change followed a red-green cycle. The tests named below failed against the
reviewed implementation before the smallest corresponding production change was applied.

| Recommendation | Resolution | Permanent proof |
|---|---|---|
| 1. Existing rows and movie parsing | `probeFile` repairs mismatched filenames; movie parsing reads the filepath URI; V049 backfills rows that never rescan. | `shouldRepairExistingMangledFilenameFromFilepathUriWhenRescanned`, `shouldSearchFromFilepathUriWhenExistingFilenameIsMangled`, `MediaFileFilenameMigrationIT` |
| 2. Sonar `Optional.get()` | Replaced the guarded `get()` calls with an `Optional`/`OptionalInt` pipeline. | Full series processor suite and source inspection; the remote Sonar gate requires publishing the changes before it can rerun. |
| 3. Mutation-sensitive guard | Added a narrowly scoped ArchUnit rule at the persistence seam. | `ArchitectureTest.persistedFilenamesMustNotComeFromPathDisplayText` |
| 4. PR description | Replaced the stale GitHub body with the actual Linux reproduction, repair/backfill behavior, compatibility contract, and verification evidence. | [PR #264](https://github.com/streamarr/streamarr-server/pull/264) |
| 5. V036 policy | Restored V036's original import and retained a delegating compatibility facade in the old package. | Full Flyway chain validates and applies through V049. |
| 6. Invalid `file:` values | Rejects malformed, opaque, query-bearing, fragment-bearing, and invalid-UTF-8 file URIs; scheme-less legacy values still work. | Eight focused codec boundary tests, including decode-to-`Path` coverage. |
| 7. Root season layout | Removed the season folder itself as a series-title fallback. | `shouldUseFilenameTitleWhenRootSeasonFolderHasNoSeriesParent` |
| 8. Diagnostics | Recommends `LC_ALL=C.UTF-8`, describes an unavailable charset clearly, and corrects macOS/stdout comments. | Two warning-log tests and the container integration suite. |
| 9. Reserved characters and colon | Pinned the passing codec-generated `%`/`+`/`#` round trip and legacy `Frost:Nixon.mkv` behavior. | Two permanent codec tests. |
| 10. Invalid UTF-8 policy | Chose fail-fast strict UTF-8 decoding for both text extraction and URI-to-`Path` conversion; narrowed Javadoc to that contract. | Two invalid-UTF-8 rejection tests. |
| 11. Season parser boundary | Parser now consumes a bare decoded folder name and handles blank input without depending on the codec. | `shouldReturnEmptySeasonResultWhenFolderNameIsBlank` plus the full parser suite. |
| 12. Container hardening | Pinned the JDK image digest, uses charset equivalence, resolves class directories from code sources, and documents the integration-test boundary. | `NonUtf8LocaleFilenameIT`: 11 passing tests in the pinned Linux container. |
| 13. `FilepathUri` value object | Deliberately not added. The existing static codec already hides URI parsing/decoding; a record forwarding the same methods would add surface area without deepening the module. | Design assessment; no behavioral defect remained uncovered by this non-change. |

Final verification on 2026-08-04:

- `./mvnw verify`: **BUILD SUCCESS**
- Unit tests: **1,692 passed**
- Integration tests: **463 passed**
- Checkstyle: **0 violations**
- Flyway: **49 migrations validated and applied through V049**
- The real non-UTF-8 locale container suite: **11 passed**
- Mainline migration check: V049 was unclaimed; `main` ended at V048 at verification time.

## Review method

- Compared the exact base and head above, including all 29 changed files (888 additions and 185
  deletions).
- Read ADR 0005 (TDD and behavior testing), ADR 0012 (filepath URI encoding), and the repository
  engineering rules.
- Re-ran the branch's focused codec/config/parser tests: 45 passed.
- Ran `NonUtf8LocaleFilenameIT` against Docker: all 11 tests passed.
- Added each reproduction test independently and ran it red. The subsequent TDD remediation retained
  every applicable reproduction as permanent coverage.
- Mutation-tested the service regression test by restoring the pre-fix production expression,
  running both the existing test and a temporary ArchUnit rule, then restoring the PR code.
- Refreshed GitHub state on 2026-08-04. CI and Snyk pass; SonarCloud Code Analysis fails with a new
  code Reliability rating of C where A is required; GitHub reports the PR as blocked.
- Used the available GitNexus index to estimate affected execution flows. That index predates the
  reviewed head, so its impact counts are lower-bound navigation evidence rather than exact current
  call-graph counts.

## Combined findings

| ID | Priority | Assessment | Finding and impact |
|---|---:|---|---|
| F1 | P1 | Confirmed by two red tests | Existing mojibake rows are returned unchanged by `LibraryManagementService.probeFile`. Movie parsing still starts from `mediaFile.filename`, so a corrupt filename that contains a year bypasses the correct folder fallback and keeps failing enrichment. Series processing now uses URI-derived names, but its persisted/displayed filename still remains corrupt. |
| F2 | Merge gate | Confirmed by GitHub/Sonar | `SeriesFileProcessor.resolveSeasonNumber` calls `Optional.get()` at line 175. The preceding predicate makes the runtime path logically safe, but Sonar reports it as a reliability bug and the mandatory quality gate fails. This is a delivery blocker even if it is a runtime false positive. |
| F3 | P2 | Confirmed by four red tests | `FilepathCodec.decodedPathComponentOf` silently treats malformed or semantically invalid `file:` URIs as legacy raw paths. Raw spaces, query strings, fragments, and opaque `file:` URIs can produce truncated or garbage metadata rather than a boundary error. Normal codec-generated URIs are unaffected. |
| F4 | P2 | Confirmed by mutation test | The current service regression test does not fail if `createNewMediaFile` is changed back to `path.getFileName().toString()`. Jimfs does not recreate the platform charset failure, while the container IT tests the codec rather than the service persistence seam. |
| F5 | P2 | Confirmed by red test | For a file directly under a root-level season folder, `seriesFolderNameOf` falls back from the absent grandparent to the season folder itself. `Season 25` then beats the correct filename-derived title. The layout is uncommon, but the result is a definite metadata search failure. |
| F6 | P3 | Confirmed by two red tests | The startup warning recommends only `LANG=C.UTF-8`, which is ineffective when `LC_ALL` is set (including the PR's own reproduction). An unavailable property also produces the confusing phrase `read ... as ''`. |
| F7 | P3 | Confirmed empirically | Comments in `NonUtf8LocaleFilenameIT` and `NonUtf8LocaleFilenameProbe` misattribute macOS `?` output to the filesystem and say `System.out` uses `sun.jnu.encoding`. On the tested macOS JVM, filesystem decoding remained UTF-8 while stdout/native encoding was US-ASCII. This does not alter runtime behavior but obscures the diagnosis. |
| F8 | P3/follow-up | Confirmed by red test; outside the declared UTF-8 contract | A URI containing non-UTF-8 percent bytes, such as `caf%E9.mkv`, is decoded with U+FFFD rather than rejected or diagnosed. This is relevant to filesystems containing legacy byte encodings, but ADR 0012 declares UTF-8 as the persisted URI contract. |
| F9 | P3/follow-up | Confirmed by red test | `SeasonPathMetadataParser.parse("")` now throws because the parser calls `FilepathCodec.filenameOf`. It previously returned a present result with no season. Product callers pass a decoded folder name, so this is a low-impact parser-contract regression and evidence that the parser should not depend on the codec. |
| F10 | P2 process | Confirmed from current PR body | The PR description is stale: it says the real container regression test only models the failure; lists processor/parser sites as unfixed even though the head fixes them; and claims existing rows repair on rescan, which F1 disproves. |
| F11 | P2 policy | Confirmed; checksum concern disproved | The PR edits the already-mainline `V036__Encode_Library_Filepath_Uri` import. `BaseJavaMigration#getChecksum()` is `null`, so this particular edit does not create a Flyway checksum mismatch. It nevertheless violates the repository's unconditional rule never to edit a migration already on `main`. |

### F1: existing rows do not self-heal

Relevant code:

- `LibraryManagementService.java:425-431` returns an existing entity without reconciling its
  filename with `FilepathCodec.filenameOf(filepathUri)`.
- `MovieFileProcessor.java:92-104` parses both normal metadata and external IDs from the stored
  filename. Its folder fallback applies only when the filename parse has no year.

Two public-service reproductions failed:

| Temporary test | Expected | Actual |
|---|---|---|
| `LibraryManagementServiceTest#shouldRepairExistingMangledFilenameFromFilepathUriWhenRescanned` | Existing `MATCHED` row changes from `D��j�� Vu...mkv` to `Déjà Vu...mkv` after `processDiscoveredFile` | Filename remains `D��j�� Vu...mkv` |
| `MovieFileProcessorTest#shouldSearchFromFilepathUriWhenExistingFilenameIsMangled` | Provider receives `Déjà Vu`, finds the configured match, and processing advances to `UNMATCHED` because the test's metadata result is empty | Parser searches for `D��j�� Vu`; status becomes `METADATA_SEARCH_FAILED` |

The second test deliberately puts the year in the corrupt stored filename. That proves the current
folder fallback is insufficient rather than merely observing a stale display column.

Impact analysis marks `probeFile` as high risk: 31 impacted symbols across four library scanning
flows. `parseMediaFileForMovieInfo` reaches eight symbols across the same four flows. The affected
entry paths include manual GraphQL scans, directory walking, stable-file processing, and file-event
processing. Every pre-existing corrupt movie row with a still-parseable year can continue to miss
metadata; all corrupt rows can continue exposing a damaged filename even where enrichment succeeds.

### F2: Sonar reliability gate is red

At `SeriesFileProcessor.java:175-176`, the code checks the optional through a helper and then uses
`get()` twice:

```java
if (isSeasonFolder(seasonParseResult) && seasonParseResult.get().seasonNumber().isPresent()) {
  return seasonParseResult.get().seasonNumber().getAsInt();
}
```

The guard is logically safe in this single-threaded local value, so no useful behavioral failing
test exists. The externally observable failure is the required Sonar quality gate itself: new-code
Reliability is C and the PR is blocked. Refactor to an `Optional` pipeline or local pattern that does
not call `Optional.get()`.

### F3: malformed `file:` values fail open

Each temporary test asserted `IllegalArgumentException` at the filepath URI boundary and instead
observed normal return:

| Temporary test | Input | Current behavior |
|---|---|---|
| `shouldRejectMalformedFileUriContainingUnescapedSpace` | `file:///media/My Movies/movie.mkv` | URI parsing fails and the entire string is treated as a raw path |
| `shouldRejectFilepathUriContainingQuery` | `file:///media/movie.mkv?download=true` | Query is silently discarded by `URI.getPath()` |
| `shouldRejectFilepathUriContainingFragment` | `file:///media/movie#1.mkv` | Fragment is silently discarded by `URI.getPath()` |
| `shouldRejectOpaqueFileUri` | `file:movie.mkv` | Opaque URI falls through to raw-path interpretation |

This is a corrupt/manual-database-value path, not a failure of `FilepathCodec.encode`. Preserve the
intentional scheme-less legacy-path fallback, but reject a malformed or unsupported value once it
declares itself to be a `file:` URI. Also reject query, fragment, and opaque forms before segment
extraction.

A guard must not reject every colon-containing legacy name. The temporary
`shouldTreatPlainFilenameContainingColonAsLegacyPath` test passed for `Frost:Nixon.mkv`; that
behavior should remain supported.

### F4: current service test survives a production regression

The mutation experiment changed only this line:

```diff
-.filename(FilepathCodec.filenameOf(absoluteFilepath))
+.filename(path.getFileName().toString())
```

Results:

1. Existing test
   `LibraryManagementServiceTest#shouldPersistFilenameDecodedFromFilepathUriWhenNameIsNonAscii`
   still passed under Jimfs.
2. A temporary, narrowly targeted ArchUnit rule forbidding `Path.getFileName()` calls from
   `LibraryManagementService.createNewMediaFile` failed at the mutated line.
3. Restoring the PR code made the ArchUnit rule pass.

This confirms the gap without treating benign `Path.getFileName()` uses (extension extraction and
dot-file checks) as defects. Keep a targeted architectural guard, or move persisted-display-name
construction behind an interface that the service cannot bypass.

### F5: root-level season folder becomes the title

The failing scenario was:

```text
file:///Season%2025/The%20Simpsons.S25E09.mkv
```

Temporary test
`SeriesFileProcessorTest#shouldUseFilenameTitleWhenRootSeasonFolderHasNoSeriesParent` configured a
provider result only for `The Simpsons`. Correct parsing would reach enrichment and finish as
`ENRICHMENT_FAILED` because the test metadata was empty. The current code searched for
`Season 25` and instead ended as `METADATA_SEARCH_FAILED`.

`SeasonPathMetadataParser.parse` is high-risk in the static impact graph (14 impacted symbols, five
direct callers, four processes), although this exact root layout should be rare. Return only
`FilepathCodec.grandparentNameOf(filepathUri)` when the parent is a season folder; an empty result
allows the existing filename-derived title fallback to run.

### F6-F9: diagnostics and residual edge cases

The following focused reproductions also failed:

| Temporary test | Observed failure | Assessment |
|---|---|---|
| `FilenameEncodingCheckTest#shouldRecommendOverridingLcAllWhenFilenameEncodingIsNotUtf8` | Log contains only `LANG=C.UTF-8`, not `LC_ALL=C.UTF-8` | Fix wording in this PR |
| `FilenameEncodingCheckTest#shouldNotClaimEmptyCharsetNameWhenFilenameEncodingIsUnavailable` | Log says filenames are read `as ''` | Fix wording in this PR |
| `FilepathCodecTest#shouldRejectFilepathUriWhosePercentEncodedBytesAreNotValidUtf8` | `file:///media/caf%E9.mkv` returns replacement text rather than throwing | Follow-up is acceptable because input violates ADR 0012; at minimum warn on U+FFFD |
| `SeasonPathMetadataParserTest#shouldReturnEmptySeasonResultWhenPathIsBlank` | Throws `IllegalArgumentException: Filepath URI has no final segment` | Low-impact follow-up; parse the already-decoded folder name directly |

The attached review also suggested appending the locale hint to every
`InvalidLibraryPathException`. That was not reproduced as a reliable filename-encoding diagnosis:
the same exception is normal for missing paths on a UTF-8 JVM. Add such a hint only if the code can
distinguish an encoding-risk condition; it should not block this PR.

For the comment issue, the direct macOS probe was:

```text
LC_ALL=POSIX LANG=POSIX java -XshowSettings:properties -version
file.encoding = UTF-8
native.encoding = US-ASCII
stdout.encoding = US-ASCII
stderr.encoding = US-ASCII
sun.jnu.encoding = UTF-8
```

Therefore the filesystem still returns the accented text on this JVM, while console rendering can
turn it into `?`. Rewrite the comments to describe the inability to reproduce ASCII filesystem
decoding on macOS and the Base64 protection against stdout encoding, without attributing stdout to
`sun.jnu.encoding`.

## False-positive assessment

### Disproved: `%`, `+`, and `#` are corrupted on the normal codec path

Temporary test
`FilepathCodecTest#shouldRoundtripPercentPlusAndHashCharactersThroughFilepathUri` encoded and decoded
`100% Legit + Bonus #1.mkv`; it passed with the exact original filename. `Path.toUri()` escapes the
reserved characters and `URI.getPath()` decodes them once. There is no current production defect.
Keep this as a regression-hardening test because a future switch to `URLDecoder` would break `+`.

### Qualified: arbitrary non-UTF-8 bytes

F8 is real for hand-authored or legacy URI bytes, but it is outside the repository's stated UTF-8
contract and does not invalidate the core PR fix. Treat strict decoding or a replacement-character
warning as defense in depth, not a reason to reject the URI-based approach.

### Disproved: the V036 import edit will break Flyway checksums

`BaseJavaMigration#getChecksum()` returns `null`; changing this import does not alter a stored Java
migration checksum. The repository policy violation remains independently valid, so the migration
should still be restored and supported through a compatibility facade or an explicitly approved
exception.

### Rejected as too broad: ban every production `Path.getFileName()` call

The defect concerns deriving persisted or user-facing text. `Path.getFileName()` is still legitimate
for extension extraction and dot-file checks. The successful mutation test targeted the persistence
seam and avoids encoding a misleading universal ban.

## Merged recommendations

### Required before merge

1. **Make the filepath URI authoritative for existing rows.** In `probeFile`, derive the expected
   filename from the URI and persist it when the existing value differs. In
   `MovieFileProcessor`, parse the URI-derived filename (and URI-derived parent where needed), not
   the stored filename. Add both F1 tests permanently. Consider a new incremental backfill migration
   if all old rows must heal without waiting for a rescan; do not modify an existing migration.
2. **Restore the Sonar reliability gate.** Rewrite `resolveSeasonNumber` without `Optional.get()` and
   confirm new-code Reliability returns to A.
3. **Add the mutation-sensitive regression guard.** Retain the targeted ArchUnit rule proven in F4,
   or introduce an equivalent seam that makes a `Path`-derived persisted display name structurally
   impossible.
4. **Correct the PR description.** Describe the real Linux-container reproduction, remove the stale
   “not fixed” list, and accurately state the behavior of existing rows.
5. **Restore V036 to its mainline contents.** If the package move requires compatibility, leave a
   delegating facade at the old package until migrations no longer compile against it, unless the
   project explicitly approves an exception to its migration policy.

### Small fixes recommended in this PR

6. Reject malformed/opaque/query/fragment-bearing `file:` values while preserving scheme-less
   legacy paths and colon-containing legacy filenames.
7. Remove the season-folder fallback in `seriesFolderNameOf` so root-level season layouts fall back
   to the episode filename.
8. Recommend `LC_ALL=C.UTF-8`, handle an unknown/empty charset name clearly, and correct the macOS
   and stdout comments.
9. Add the passing `%`/`+`/`#` round-trip test and the passing `Frost:Nixon.mkv` legacy-path test to
   pin the intended guard behavior.

### Follow-up hardening

10. Decide whether invalid UTF-8 URI bytes should fail fast or emit a once-per-path warning, and
    narrow the `FilepathCodec` Javadoc so it does not imply arbitrary bytes can always become valid
    UTF-8 text.
11. Make `SeasonPathMetadataParser` parse a bare decoded folder name and restore its blank-input
    behavior.
12. Pin the container image by digest, compare charsets by equivalence rather than one glibc alias,
    resolve compiled-class paths independently of the IDE working directory, and document why this
    is an integration test.
13. Consider a `FilepathUri` value object (`filename`, `parentName`, `toPath`) so accidental
    user-facing `Path.toString()` calls become harder to express.

## Out-of-scope debt surfaced during review

These observations predate the PR and should be tracked separately rather than charged to #264:

- The scan executor discards exceptions into unread `Future` instances. New fail-fast codec paths
  would make that existing observability gap more noticeable.
- `MovieFileProcessor`'s empty-metadata branch has questionable status behavior; the current tests
  encode it.
- A tautological assertion in `LibraryManagementServiceTest` looks like regression coverage but
  does not constrain production behavior.

## Reproduction command pattern

Each temporary Java test was applied individually and run with Java 25:

```shell
./mvnw test -Dtest=<TestClass>#<temporaryMethod>
```

The Mockito-using service tests needed normal JVM self-attachment permissions in the execution
environment. No temporary mutation remains in the worktree; the production changes and permanent
tests are summarized in the TDD remediation record above.
