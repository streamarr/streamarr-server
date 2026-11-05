#!/usr/bin/env python3
"""Reject migrations a pull request adds at or below the base branch's highest Flyway version.

Flyway refuses to start a database that has applied a higher version than a pending migration,
so a migration merged below the base branch's highest version breaks every existing database.
"""

import re
import subprocess
import sys

MIGRATION_DIRECTORIES = ("src/main/resources/db/migration", "src/main/java/db/migration")
# Flyway also reads signed, Unicode, aliased and uppercase-suffixed names, in ways that differ
# from this pattern. Any other file whose name starts with V is unreadable and fails the check.
MIGRATION_NAME = re.compile(r"V(?P<version>[0-9]{1,18}(?:[._][0-9]{1,18})*)__[^/]*\.(?:sql|java)")


def git(*arguments):
    return subprocess.run(["git", *arguments], capture_output=True, text=True, check=True).stdout


def paths(output):
    return [path for path in output.split("\0") if path]


def fetch(branch):
    command = ["fetch", "--quiet", "--no-tags"]
    # The merge base is unreachable from a shallow checkout.
    if git("rev-parse", "--is-shallow-repository").strip() == "true":
        command.append("--unshallow")
    git(*command, "origin", f"+refs/heads/{branch}:refs/remotes/origin/{branch}")
    return f"refs/remotes/origin/{branch}"


def is_migration(path):
    name = path.rsplit("/", 1)[-1]
    # V119__x.sql.conf configures V119__x.sql (for example executeInTransaction=false).
    return name.startswith("V") and not name.endswith(".sql.conf")


def version(path):
    """Return the version's sort key and label, or None when the name is unreadable."""
    match = MIGRATION_NAME.fullmatch(path.rsplit("/", 1)[-1])
    if match is None:
        return None
    parts = [int(part) for part in re.split(r"[._]", match["version"])]
    # Flyway treats trailing zero parts as equal: 1.0 is 1.
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts), f"V{match['version']}"


def introduced(changes):
    """Yield the migrations a diff introduces: additions, and renames that change the version."""
    fields = iter(paths(changes))
    for status in fields:
        source = next(fields) if status.startswith("R") else None
        path = next(fields)
        if not is_migration(path):
            continue
        parsed = version(path)
        previous = version(source) if source is not None else None
        if parsed is not None and previous is not None and previous[0] == parsed[0]:
            continue
        yield path, parsed


def escape(value, property_value=False):
    value = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    if property_value:
        value = value.replace(":", "%3A").replace(",", "%2C")
    return value


def error(path, title, message):
    print(f"::error file={escape(path, True)},title={escape(title, True)}::{escape(message)}")


def main(arguments):
    if len(arguments) != 1:
        print(f"Usage: {sys.argv[0]} <base-branch>", file=sys.stderr)
        return 2
    branch = arguments[0]
    base = fetch(branch)
    merge_base = git("merge-base", base, "HEAD").strip()
    listing = git("ls-tree", "-r", "-z", "--name-only", base, "--", *MIGRATION_DIRECTORIES)
    existing = {path: version(path) for path in paths(listing) if is_migration(path)}
    added = dict(introduced(git("diff", "--name-status", "-z", "--diff-filter=AR",
                                "--find-renames", merge_base, "HEAD", "--",
                                *MIGRATION_DIRECTORIES)))

    unreadable_base = sorted(path for path, parsed in existing.items() if parsed is None)
    for path in unreadable_base:
        error(path, "Unreadable Flyway migration name",
              f"{branch} holds a migration name this check cannot read, so it cannot find "
              f"{branch}'s highest Flyway version. Name it V<digits>__<description>.sql or .java.")
    if unreadable_base:
        return 1

    if existing:
        highest_path, (highest, highest_label) = max(existing.items(), key=lambda item: item[1][0])
    else:
        highest_path, highest, highest_label = "no migration", (), "none"
    failed = False
    for path, parsed in sorted(added.items()):
        if path in existing:
            continue
        if parsed is None:
            failed = True
            error(path, "Unreadable Flyway migration name",
                  f"Flyway may read this name as a version at or below {branch}'s highest "
                  f"Flyway version {highest_label} ({highest_path}). Name it "
                  f"V<digits>__<description>.sql or .java with a version above {highest_label}.")
        elif parsed[0] <= highest:
            failed = True
            error(path, "Out-of-order Flyway migration",
                  f"{parsed[1]} is at or below {branch}'s highest Flyway version "
                  f"{highest_label} ({highest_path}). A database that applied {highest_label} "
                  f"refuses to start with a lower pending migration; renumber it above "
                  f"{highest_label}.")

    if failed:
        return 1

    print(f"Every added Flyway migration sorts above {branch}'s highest version "
          f"{highest_label} ({highest_path})")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except subprocess.CalledProcessError as failure:
        print(f"::error title=Flyway migration order check::git {' '.join(failure.cmd[1:])} "
              f"failed: {escape(failure.stderr.strip())}")
        sys.exit(2)
