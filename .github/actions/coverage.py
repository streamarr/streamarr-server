#!/usr/bin/env python3
"""Validate the artifacts exchanged by the parallel application test jobs."""

import argparse
import fnmatch
import hashlib
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

COMPILER_OUTPUTS = Path(
    "target/maven-status/maven-compiler-plugin/compile/default-compile/createdFiles.lst"
)
AUTHORIZATION = Path("com/streamarr/server/services/authorization")


def compiled_classes():
    classes = set(Path("target/classes").rglob("*.class"))
    if not classes:
        raise ValueError("No compiled classes found in target/classes")
    # Use the compiler's inventory so two identically truncated trees cannot agree on a hash.
    expected = {
        Path("target/classes") / name for name in COMPILER_OUTPUTS.read_text().splitlines()
    }
    if classes != expected:
        raise ValueError(
            f"Compiler output mismatch: missing={sorted(expected - classes)}, "
            f"unexpected={sorted(classes - expected)}"
        )
    return sorted(classes)


def fingerprint(suite):
    classes = compiled_classes()
    Path(f"target/ci-{suite}-classes.sha256").write_text(class_manifest(classes))


def class_manifest(classes):
    return "".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.as_posix()}\n"
        for path in classes
    )


def authorization_classes(classes):
    source_root = Path("src/main/java")
    sources = {
        Path("target/classes") / path.relative_to(source_root).with_suffix(".class")
        for path in (source_root / AUTHORIZATION).rglob("*.java")
        if path.name != "package-info.java"
    }
    if not sources:
        raise ValueError("No authorization sources found in the checkout")
    missing = sources - set(classes)
    if missing:
        raise ValueError(f"Authorization sources were not compiled: {sorted(missing)}")
    return {path for path in classes if Path("target/classes") / AUTHORIZATION in path.parents}


def verify_inputs():
    classes = compiled_classes()
    authorization_classes(classes)
    expected = class_manifest(classes)
    for suite in ("unit", "integration"):
        execution = Path(f"target/jacoco-output/jacoco-{suite}-tests.exec")
        if execution.stat().st_size == 0:
            raise ValueError(f"Empty {suite} execution data: {execution}")
        manifest = Path(f"target/ci-{suite}-classes.sha256")
        if manifest.read_text() != expected:
            raise ValueError(f"{suite} class fingerprints do not match compiled classes")


def verify_report():
    merged = Path("target/jacoco-output/merged.exec")
    if merged.stat().st_size == 0:
        raise ValueError("Empty merged execution data")
    namespaces = {"m": "http://maven.apache.org/POM/4.0.0"}
    exclusions = [
        element.text
        for element in ET.parse("pom.xml").findall(
            ".//m:plugin[m:artifactId='jacoco-maven-plugin']/m:configuration/m:excludes/m:exclude",
            namespaces,
        )
    ]
    expected = {
        path.relative_to("target/classes").with_suffix("").as_posix()
        for path in authorization_classes(compiled_classes())
        if not any(fnmatch.fnmatchcase(path.as_posix(), pattern) for pattern in exclusions)
    }
    report = ET.parse("target/site/jacoco-merged-test-coverage-report/jacoco.xml")
    reported = {
        node.attrib["name"]
        for node in report.findall("package/class")
        if node.attrib["name"].startswith(AUTHORIZATION.as_posix() + "/")
    }
    if not expected or expected != reported:
        raise ValueError(
            f"Authorization report scope mismatch: missing={sorted(expected - reported)}, "
            f"unexpected={sorted(reported - expected)}"
        )
    covered_lines = sum(
        verify_class_coverage(node)
        for node in report.findall("package/class")
        if node.attrib["name"] in expected
    )
    if covered_lines == 0:
        raise ValueError("No authorization lines were measured")
    print(f"Verified {len(expected)} authorization classes and {covered_lines} covered lines")


def verify_class_coverage(node):
    covered_lines = 0
    for counter in node.findall("counter"):
        if counter.attrib["type"] not in ("LINE", "BRANCH"):
            continue
        if int(counter.attrib["missed"]) != 0:
            raise ValueError(f"Incomplete authorization coverage: {node.attrib['name']}")
        if counter.attrib["type"] == "LINE":
            covered_lines += int(counter.attrib["covered"])
    return covered_lines


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("fingerprint").add_argument("suite", choices=["unit", "integration"])
    commands.add_parser("verify-inputs")
    commands.add_parser("verify-report")
    args = parser.parse_args()
    actions = {
        "fingerprint": lambda: fingerprint(args.suite),
        "verify-inputs": verify_inputs,
        "verify-report": verify_report,
    }
    try:
        actions[args.command]()
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Coverage validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
