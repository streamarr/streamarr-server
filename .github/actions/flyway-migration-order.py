#!/usr/bin/env python3
"""Reject migrations a pull request adds at or below the base branch's highest Flyway version.

Flyway refuses to start a database that has applied a higher version than a pending migration,
so a migration merged below the base branch's highest version breaks every existing database.
"""

import re
import subprocess
import sys

MIGRATION_DIRECTORIES = ("src/main/resources/db/migration", "src/main/java/db/migration")
VERSIONED_MIGRATION = re.compile(r"V(?P<version>\d+(?:[._]\d+)*)__.+\.(?:sql|java)")


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


def version(path):
    match = VERSIONED_MIGRATION.fullmatch(path.rsplit("/", 1)[-1])
    if match is None:
        return None
    raw = match["version"]
    parts = [int(part) for part in re.split(r"[._]", raw)]
    # Flyway treats trailing zero parts as equal: 1.0 is 1.
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts), f"V{raw}"


def migrations(listing):
    return {path: parsed for path in paths(listing) if (parsed := version(path)) is not None}


def escape(value, property_value=False):
    value = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    if property_value:
        value = value.replace(":", "%3A").replace(",", "%2C")
    return value


def main(arguments):
    if len(arguments) != 1:
        print(f"Usage: {sys.argv[0]} <base-branch>", file=sys.stderr)
        return 2
    branch = arguments[0]
    base = fetch(branch)
    merge_base = git("merge-base", base, "HEAD").strip()
    existing = migrations(git("ls-tree", "-r", "-z", "--name-only", base, "--",
                              *MIGRATION_DIRECTORIES))
    added = migrations(git("diff", "--name-only", "-z", "--diff-filter=A", "--find-renames",
                           merge_base, "HEAD", "--", *MIGRATION_DIRECTORIES))
    if not existing:
        print(f"{branch} has no versioned Flyway migrations")
        return 0

    highest_path, (highest, highest_label) = max(existing.items(), key=lambda item: item[1][0])
    rejected = [(path, label) for path, (key, label) in sorted(added.items())
                if path not in existing and key <= highest]
    for path, label in rejected:
        message = (f"{label} is at or below {branch}'s highest Flyway version {highest_label} "
                   f"({highest_path}). A database that applied {highest_label} refuses to start "
                   f"with a lower pending migration; renumber it above {highest_label}.")
        print(f"::error file={escape(path, True)},title=Out-of-order Flyway migration::"
              f"{escape(message)}")

    if rejected:
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
