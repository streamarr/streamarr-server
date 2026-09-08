import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const generator = fileURLToPath(
    new URL("../bin/generate-notices.mjs", import.meta.url),
);
const checksum = (text) => createHash("sha256").update(text).digest("hex");

function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "ffmpeg-notices-test-"));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    fs.mkdirSync(path.join(root, "notices"));
    const text = "Copyright Example\nUnique license terms.  \n\n";
    fs.writeFileSync(path.join(root, "notices/COPYING.md.txt"), text);
    fs.writeFileSync(path.join(root, "notices/duplicate.txt"), text);
    const component = {
        id: "ffmpeg",
        repository: "https://github.com/jellyfin/jellyfin-ffmpeg",
        revision: "1".repeat(40),
        architectures: ["amd64", "arm64"],
        role: "FFmpeg executable",
        distribution: "runtime",
        licenseExpression: "GPL-3.0-or-later",
        revisionEvidence: "source",
        notices: [
            {
                file: "COPYING.md.txt",
                sha256: checksum(text),
                url: "https://example.org/COPYING.md",
            },
        ],
    };
    const components = [
        component,
        {
            ...component,
            id: "example",
            role: "Embedded library",
            licenseExpression: "MIT",
            notices: [{ ...component.notices[0], file: "duplicate.txt" }],
        },
    ];
    fs.writeFileSync(
        path.join(root, "ffmpeg.lock"),
        [
            "release=v8.1.2-4",
            "version=8.1.2-4",
            `source_revision=${component.revision}`,
            "asset_variant=gpl",
            "amd64_asset=jellyfin-ffmpeg_8.1.2-4_portable_linux64-gpl.tar.xz",
            `amd64_sha256=${"a".repeat(64)}`,
            "arm64_asset=jellyfin-ffmpeg_8.1.2-4_portable_linuxarm64-gpl.tar.xz",
            `arm64_sha256=${"b".repeat(64)}`,
        ].join("\n") + "\n",
    );
    fs.writeFileSync(
        path.join(root, "notices/manifest"),
        `release=v8.1.2-4\nsource_revision=${"1".repeat(40)}\namd64_sha256=${"a".repeat(64)}\narm64_sha256=${"b".repeat(64)}\n`,
    );
    fs.writeFileSync(
        path.join(root, "SOURCE.txt"),
        `Corresponding Source: ${component.repository} ${component.revision}\n`,
    );
    fs.writeFileSync(path.join(root, "LICENSE.txt"), "GPL license text\n");
    for (const arch of ["amd64", "arm64"]) {
        fs.writeFileSync(
            path.join(root, `notices/buildconf-${arch}.txt`),
            `configuration: ${arch}\n`,
        );
    }
    const writeInventory = () =>
        fs.writeFileSync(
            path.join(root, "notices/sources.json"),
            JSON.stringify(components),
        );
    writeInventory();
    return { root, text, components, writeInventory };
}

function run(root, ...args) {
    const result = spawnSync(
        process.execPath,
        [generator, "--root", root, ...args],
        { encoding: "utf8", timeout: 15000 },
    );
    assert.ifError(result.error);
    return { status: result.status, output: result.stdout + result.stderr };
}

test("Should preserve verbatim notices once and normalize redundant document extensions", (t) => {
    const { root, text } = fixture(t);

    const result = run(root);

    assert.equal(result.status, 0, result.output);
    const document = fs.readFileSync(
        path.join(root, "generated/THIRD-PARTY-NOTICES.txt"),
        "utf8",
    );
    assert.equal(document.split(text).length - 1, 1);
    assert.match(document, /ffmpeg/);
    assert.match(document, /example/);
    assert.match(document, /COPYING\.md/);
    assert.doesNotMatch(document, /\.md\.txt/);
});

for (const version of [null, "v20.20.2"]) {
    test(`Should explain the Node prerequisite when the available version is ${version ?? "missing"}`, (t) => {
        const { root } = fixture(t);
        const commands = path.join(root, "commands");
        fs.mkdirSync(commands);
        if (version) {
            fs.writeFileSync(
                path.join(commands, "node"),
                `#!/bin/bash\necho ${version}\n`,
                { mode: 0o755 },
            );
        }
        const prepare = fileURLToPath(
            new URL("../bin/prepare", import.meta.url),
        );

        const result = spawnSync("/bin/bash", [prepare, "--validate"], {
            encoding: "utf8",
            timeout: 15000,
            env: { ...process.env, PATH: commands },
        });

        assert.ifError(result.error);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /FFmpeg tooling requires Node.js/);
        assert.match(result.stderr, /nvm install && nvm use/);
    });
}

test("Should verify checksums with shasum when GNU sha256sum is unavailable", (t) => {
    const { root, text } = fixture(t);
    const commands = path.join(root, "commands");
    fs.mkdirSync(commands);
    const lookup = spawnSync("/bin/bash", ["-c", "command -v shasum"], {
        encoding: "utf8",
    });
    assert.equal(
        lookup.status,
        0,
        "Expected the portable host checksum utility shasum",
    );
    fs.symlinkSync(lookup.stdout.trim(), path.join(commands, "shasum"));
    const sums = path.join(root, "SHA256SUMS");
    fs.writeFileSync(
        sums,
        `${checksum(text)}  ${path.join(root, "notices/COPYING.md.txt")}\n`,
    );
    const helper = fileURLToPath(
        new URL("../lib/checksum.sh", import.meta.url),
    );
    const command = [
        "-c",
        '. "$1"; ffmpeg_sha256_check --check --strict "$2"',
        "--",
        helper,
        sums,
    ];

    const result = spawnSync("/bin/bash", command, {
        encoding: "utf8",
        timeout: 15000,
        env: { ...process.env, PATH: commands },
    });

    assert.ifError(result.error);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /COPYING.md.txt: OK/);
    fs.unlinkSync(path.join(commands, "shasum"));
    const missing = spawnSync("/bin/bash", command, {
        encoding: "utf8",
        timeout: 15000,
        env: { ...process.env, PATH: commands },
    });
    assert.notEqual(missing.status, 0);
    assert.match(missing.stderr, /requires sha256sum or shasum/);
});

test("Should validate source inputs without requiring or writing generated artifacts", (t) => {
    const { root } = fixture(t);
    const manifest =
        [
            "release=v8.1.2-4",
            `source_revision=${"1".repeat(40)}`,
            `amd64_sha256=${"a".repeat(64)}`,
            `arm64_sha256=${"b".repeat(64)}`,
        ].join("\n") + "\n";
    fs.writeFileSync(path.join(root, "notices/manifest"), manifest);

    const result = run(root, "--validate");

    assert.equal(result.status, 0, result.output);
    assert.equal(fs.existsSync(path.join(root, "generated")), false);
    assert.equal(
        fs.readFileSync(path.join(root, "notices/manifest"), "utf8"),
        manifest,
    );
});

test("Should reject missing source-access instructions during offline validation", (t) => {
    const { root } = fixture(t);
    fs.writeFileSync(path.join(root, "SOURCE.txt"), "stale instructions");

    const result = run(root, "--validate");

    assert.notEqual(result.status, 0);
    assert.match(result.output, /SOURCE.txt.*ffmpeg/);
    assert.equal(fs.existsSync(path.join(root, "generated")), false);
});

test("Should reject changed notice contents before writing redistribution materials", (t) => {
    const { root } = fixture(t);
    fs.appendFileSync(
        path.join(root, "notices/COPYING.md.txt"),
        "unreviewed change",
    );

    const result = run(root);

    assert.notEqual(result.status, 0);
    assert.match(result.output, /Notice checksum mismatch: COPYING\.md\.txt/);
    assert.equal(fs.existsSync(path.join(root, "generated")), false);
});

test("Should report stale generated output without repairing it in check mode", (t) => {
    const { root } = fixture(t);
    assert.equal(run(root).status, 0);
    assert.equal(run(root, "--check").status, 0);
    const output = path.join(root, "generated/THIRD-PARTY-NOTICES.txt");
    fs.writeFileSync(output, "stale output");

    const result = run(root, "--check");

    assert.notEqual(result.status, 0);
    assert.match(result.output, /Stale generated file/);
    assert.equal(fs.readFileSync(output, "utf8"), "stale output");
});

test("Should generate architecture-specific SBOMs without presenting build inputs or unproven versions as runtime facts", (t) => {
    const { root, components, writeInventory } = fixture(t);
    components.push(
        { ...components[1], id: "arm-only", architectures: ["arm64"] },
        { ...components[1], id: "build-tool", distribution: "build-input" },
        {
            ...components[1],
            id: "generator-output",
            distribution: "embedded",
            revisionEvidence: "notice-only",
        },
    );
    writeInventory();

    const result = run(root);

    assert.equal(result.status, 0, result.output);
    const amd64 = JSON.parse(
        fs.readFileSync(path.join(root, "generated/ffmpeg.amd64.cdx.json")),
    );
    const arm64 = JSON.parse(
        fs.readFileSync(path.join(root, "generated/ffmpeg.arm64.cdx.json")),
    );
    assert.equal(amd64.bomFormat, "CycloneDX");
    assert.deepEqual(
        amd64.components.map((c) => c.name),
        ["FFmpeg", "example", "generator-output"],
    );
    assert.ok(arm64.components.some((c) => c.name === "arm-only"));
    assert.equal(amd64.formulation[0].components[0].name, "build-tool");
    assert.equal(
        amd64.components[0].purl,
        "pkg:github/jellyfin/jellyfin-ffmpeg@v8.1.2-4",
    );
    assert.equal(amd64.components[0].hashes[0].content, "a".repeat(64));
    assert.equal(arm64.components[0].hashes[0].content, "b".repeat(64));
    const generated = amd64.components.find(
        (c) => c.name === "generator-output",
    );
    assert.equal(generated.version, undefined);
    assert.ok(
        generated.properties.some(
            (p) => p.name === "streamarr:notice-source-revision",
        ),
    );
    assert.ok(
        !generated.properties.some(
            (p) => p.name === "streamarr:source-revision",
        ),
    );
});

test("Should bind generated materials to all inputs without changing the reviewed manifest", (t) => {
    const { root, components } = fixture(t);
    const manifest = fs.readFileSync(path.join(root, "notices/manifest"));

    const result = run(root);

    assert.equal(result.status, 0, result.output);
    assert.deepEqual(
        fs.readFileSync(path.join(root, "notices/manifest")),
        manifest,
    );
    const sums = fs.readFileSync(
        path.join(root, "generated/SHA256SUMS"),
        "utf8",
    );
    for (const file of [
        "ffmpeg.lock",
        "SOURCE.txt",
        "notices/sources.json",
        "notices/buildconf-amd64.txt",
        "notices/buildconf-arm64.txt",
        "notices/COPYING.md.txt",
        "notices/duplicate.txt",
        "generated/THIRD-PARTY-NOTICES.txt",
        "generated/ffmpeg.amd64.cdx.json",
        "generated/ffmpeg.arm64.cdx.json",
    ]) {
        assert.ok(
            sums.includes(
                `${checksum(fs.readFileSync(path.join(root, file)))}  ${file}\n`,
            ),
            file,
        );
    }
    const review = JSON.parse(
        fs.readFileSync(path.join(root, "generated/review-inputs.json")),
    );
    assert.equal(review.components.length, components.length);
    fs.appendFileSync(
        path.join(root, "notices/buildconf-arm64.txt"),
        "--enable-new-library\n",
    );
    assert.notEqual(run(root, "--check").status, 0);
});

for (const invalid of [
    "empty",
    "duplicate",
    "architecture",
    "license",
    "distribution",
    "revision",
    "escape",
    "symlink",
]) {
    test(`Should reject ${invalid} inventory before writing outputs`, (t) => {
        const { root, components, writeInventory } = fixture(t);
        switch (invalid) {
            case "empty":
                components.length = 0;
                break;
            case "duplicate":
                components.push(components[0]);
                break;
            case "architecture":
                components[1].architectures = ["riscv64"];
                break;
            case "license":
                delete components[1].licenseExpression;
                break;
            case "distribution":
                components[1].distribution = "unknown";
                break;
            case "revision":
                components[0].revision = "2".repeat(40);
                break;
            case "escape":
                components[0].notices[0].file = "../SOURCE.txt";
                break;
            case "symlink": {
                const link = path.join(root, "notices/COPYING.md.txt");
                fs.unlinkSync(link);
                fs.symlinkSync("duplicate.txt", link);
                break;
            }
        }
        writeInventory();

        const result = run(root);

        assert.notEqual(result.status, 0, invalid);
        assert.equal(fs.existsSync(path.join(root, "generated")), false);
    });
}

test("Should render stable notices and SBOMs regardless of inventory ordering", (t) => {
    const { root, components, writeInventory } = fixture(t);
    assert.equal(run(root).status, 0);
    const before = fs.readFileSync(
        path.join(root, "generated/THIRD-PARTY-NOTICES.txt"),
    );
    const sbom = fs.readFileSync(
        path.join(root, "generated/ffmpeg.amd64.cdx.json"),
    );
    components.reverse();
    writeInventory();

    assert.equal(run(root).status, 0);

    assert.deepEqual(
        fs.readFileSync(path.join(root, "generated/THIRD-PARTY-NOTICES.txt")),
        before,
    );
    assert.deepEqual(
        fs.readFileSync(path.join(root, "generated/ffmpeg.amd64.cdx.json")),
        sbom,
    );
});

test("Should include the actual libunibreak license rather than only its README", () => {
    const root = fileURLToPath(new URL("..", import.meta.url));
    const components = JSON.parse(
        fs.readFileSync(path.join(root, "notices/sources.json")),
    );
    const component = components.find((entry) => entry.id === "libunibreak");
    const license = component.notices.find((notice) =>
        notice.url.endsWith("/LICENCE"),
    );

    assert.ok(license, "Missing pinned libunibreak LICENCE");
    assert.match(
        fs.readFileSync(path.join(root, "notices", license.file), "utf8"),
        /Copyright \(C\) Wu Yongwei/,
    );
});

test("Should report recipe and build-configuration changes without updating review approval", (t) => {
    const { root, components, writeInventory } = fixture(t);
    assert.equal(run(root).status, 0);
    const snapshot = path.join(root, "generated/review-inputs.json");
    const before = fs.readFileSync(snapshot);
    const manifest = fs.readFileSync(path.join(root, "notices/manifest"));
    components[1].recipe = "changed-build-recipe";
    writeInventory();
    fs.appendFileSync(
        path.join(root, "notices/buildconf-arm64.txt"),
        "--enable-extra\n",
    );

    const result = run(root, "--compare", snapshot);

    assert.equal(result.status, 0, result.output);
    assert.match(result.output, /Changed component: example \(recipe\)/);
    assert.match(result.output, /Changed input: notices\/buildconf-arm64\.txt/);
    assert.deepEqual(fs.readFileSync(snapshot), before);
    assert.deepEqual(
        fs.readFileSync(path.join(root, "notices/manifest")),
        manifest,
    );
});

test("Should compare added and removed components and changed lock fields without writing outputs", (t) => {
    const { root, components, writeInventory } = fixture(t);
    assert.equal(run(root).status, 0);
    const snapshot = path.join(root, "generated/review-inputs.json");
    const before = fs.readFileSync(snapshot);
    components[1] = { ...components[1], id: "new-component" };
    writeInventory();
    const lock = path.join(root, "ffmpeg.lock");
    fs.writeFileSync(
        lock,
        fs.readFileSync(lock, "utf8").replace("a".repeat(64), "c".repeat(64)),
    );

    const result = run(root, "--compare", snapshot);

    assert.equal(result.status, 0, result.output);
    assert.match(result.output, /Added component: new-component/);
    assert.match(result.output, /Removed component: example/);
    assert.match(result.output, /Changed lock: amd64_sha256/);
    assert.deepEqual(fs.readFileSync(snapshot), before);
});

test("Should reject unrecognized approval-manifest entries during offline validation", (t) => {
    const { root } = fixture(t);
    fs.appendFileSync(
        path.join(root, "notices/manifest"),
        "extra=unreviewed\n",
    );

    const result = run(root, "--validate");

    assert.notEqual(result.status, 0);
    assert.match(result.output, /notice manifest.*four entries/);
});

test("Should map component declarations and exact source notices in both generated formats", (t) => {
    const { root, components, writeInventory } = fixture(t);
    components[1].licenseExpression = "LicenseRef-example";
    components[1].recipe = "builder/example.sh";
    components[1].version_note = "Revision identifies the notice source only.";
    writeInventory();

    const result = run(root);

    assert.equal(result.status, 0, result.output);
    const document = fs.readFileSync(
        path.join(root, "generated/THIRD-PARTY-NOTICES.txt"),
        "utf8",
    );
    assert.match(document, /License expression: LicenseRef-example/);
    assert.match(
        document,
        /Source: https:\/\/github.com\/jellyfin\/jellyfin-ffmpeg/,
    );
    assert.match(document, /Origin: https:\/\/example.org\/COPYING.md/);
    const bom = JSON.parse(
        fs.readFileSync(path.join(root, "generated/ffmpeg.amd64.cdx.json")),
    );
    const component = bom.components.find((entry) => entry.name === "example");
    assert.ok(
        component.externalReferences.some(
            (reference) =>
                reference.type === "license" &&
                reference.url === components[1].notices[0].url,
        ),
    );
    assert.ok(
        component.externalReferences
            .filter((reference) => reference.type === "license")
            .every((reference) => !reference.hashes),
        "Excerpt and newline-normalized hashes identify vendored text, not necessarily the full origin URL contents",
    );
    assert.ok(
        component.properties.some(
            (property) =>
                property.name === "streamarr:recipe" &&
                property.value === components[1].recipe,
        ),
    );
    assert.ok(
        component.properties.some(
            (property) => property.name === "streamarr:version-note",
        ),
    );
    const sums = fs.readFileSync(
        path.join(root, "generated/SHA256SUMS"),
        "utf8",
    );
    assert.match(sums, /  LICENSE.txt\n/);
});

for (const target of [
    "generated",
    "generated/THIRD-PARTY-NOTICES.txt",
    "notices/sources.json",
    "ffmpeg.lock",
]) {
    test(`Should reject symlink ${target} without changing its target`, (t) => {
        const { root } = fixture(t);
        assert.equal(run(root).status, 0);
        const original = path.join(root, target);
        const saved = path.join(root, "saved");
        fs.renameSync(original, saved);
        fs.symlinkSync(saved, original);

        const result = run(root);

        assert.notEqual(result.status, 0, result.output);
        assert.match(result.output, /Symlink/);
    });
}

test("Should reject missing revision evidence instead of claiming a source revision", (t) => {
    const { root, components, writeInventory } = fixture(t);
    delete components[1].revisionEvidence;
    writeInventory();

    assert.notEqual(run(root).status, 0);
});

for (const invalid of ["duplicate", "asset", "version", "checksum"]) {
    test(`Should reject ${invalid} lock data before generating materials`, (t) => {
        const { root } = fixture(t);
        const file = path.join(root, "ffmpeg.lock");
        const original = fs.readFileSync(file, "utf8");
        const changed = {
            duplicate: original + "version=8.1.2-4\n",
            asset: original.replace("linux64-gpl", "linux64-lgpl"),
            version: original.replace("version=8.1.2-4", "version=9.0.0-1"),
            checksum: original.replace("a".repeat(64), "not-a-checksum"),
        };
        fs.writeFileSync(file, changed[invalid]);

        const result = run(root);

        assert.notEqual(result.status, 0);
        assert.equal(fs.existsSync(path.join(root, "generated")), false);
    });
}
