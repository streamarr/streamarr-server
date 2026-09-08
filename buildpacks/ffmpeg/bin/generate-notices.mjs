import fs from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { spawnSync } from "node:child_process";

const { values } = parseArgs({
    options: {
        root: { type: "string" },
        check: { type: "boolean" },
        compare: { type: "string" },
    },
});
if (values.check && values.compare)
    throw new Error("Choose --check or --compare");
const root = path.resolve(
    values.root ?? fileURLToPath(new URL("..", import.meta.url)),
);
const components = JSON.parse(readInput("notices/sources.json"));
const lockBytes = readInput("ffmpeg.lock");
const validation = spawnSync(
    "bash",
    [
        "-c",
        '. "$1"; ffmpeg_lock_validate "$2"',
        "--",
        fileURLToPath(new URL("../lib/lock.sh", import.meta.url)),
        path.join(root, "ffmpeg.lock"),
    ],
    { encoding: "utf8", timeout: 15000 },
);
if (validation.error || validation.status !== 0)
    throw new Error(validation.error?.message ?? validation.stderr);
const lock = Object.fromEntries(
    lockBytes
        .toString()
        .trim()
        .split("\n")
        .map((line) => line.split("=")),
);
if (!Array.isArray(components) || components.length === 0)
    throw new Error("Expected a nonempty component inventory");
if (
    new Set(components.map((component) => component.id)).size !==
    components.length
)
    throw new Error("Duplicate component id");
if (
    components.find((component) => component.id === "ffmpeg")?.revision !==
    lock.source_revision
) {
    throw new Error("FFmpeg inventory source revision contradicts lock");
}
components.forEach(validateComponent);
components.sort((left, right) => {
    if (left.id === right.id) return 0;
    if (left.id === "ffmpeg") return -1;
    if (right.id === "ffmpeg") return 1;
    return left.id < right.id ? -1 : 1;
});
const notices = new Map();
const sections = [
    "FFmpeg third-party notices\n",
    "Each component references the verbatim texts below by SHA-256. Identical texts are reproduced once.\n" +
        "LicenseRef identifiers refer to the complete notice bundle of the named component, not a new license.\n" +
        "Expressions are inventory declarations; the preserved texts govern. See SOURCE.txt for source access.\n",
];
for (const component of components) {
    sections.push(
        `Component: ${component.id}\n${component.role}\n` +
            `License expression: ${component.licenseExpression}\n` +
            `Distribution: ${component.distribution}\nArchitectures: ${component.architectures.join(", ")}\n` +
            `Source: ${component.repository}\nRevision (${component.revisionEvidence}): ${component.revision}\n` +
            `Recipe: ${component.recipe ?? "Not recorded"}\n${component.version_note ?? ""}\n`,
    );
    for (const notice of component.notices) {
        const bytes = readInput(`notices/${notice.file}`);
        if (
            createHash("sha256").update(bytes).digest("hex") !== notice.sha256
        ) {
            throw new Error(`Notice checksum mismatch: ${notice.file}`);
        }
        sections.push(`Notice: ${notice.sha256}\nOrigin: ${notice.url}\n`);
        notices.set(notice.sha256, notices.get(notice.sha256) ?? notice);
    }
}
for (const [digest, notice] of notices) {
    const name = notice.file.replace(/\.(md|txt)\.txt$/i, ".$1");
    sections.push(`\nNotice: ${digest}\nFile: ${name}\n\n`);
    sections.push(readInput(`notices/${notice.file}`));
}
const content = Buffer.concat(
    sections.flatMap((section) => [Buffer.from(section), Buffer.from("\n")]),
);
const outputs = new Map([["THIRD-PARTY-NOTICES.txt", content]]);
for (const arch of ["amd64", "arm64"]) {
    outputs.set(
        `ffmpeg.${arch}.cdx.json`,
        Buffer.from(JSON.stringify(sbom(arch), null, 2) + "\n"),
    );
}
const inputFiles = new Set([
    "ffmpeg.lock",
    "SOURCE.txt",
    "LICENSE.txt",
    "notices/sources.json",
    "notices/buildconf-amd64.txt",
    "notices/buildconf-arm64.txt",
    ...components.flatMap((component) =>
        component.notices.map((notice) => `notices/${notice.file}`),
    ),
]);
const inputs = Object.fromEntries(
    [...inputFiles]
        .sort()
        .map((file) => [
            file,
            createHash("sha256").update(readInput(file)).digest("hex"),
        ]),
);
const review = { lock, components, inputs };
if (values.compare) {
    const previous = JSON.parse(fs.readFileSync(values.compare, "utf8"));
    reportChanges(previous, review);
    process.exit(0);
}
outputs.set(
    "review-inputs.json",
    Buffer.from(JSON.stringify(review, null, 2) + "\n"),
);
const sums = Object.entries(inputs).map(
    ([file, digest]) => `${digest}  ${file}\n`,
);
for (const [name, bytes] of outputs) {
    sums.push(
        `${createHash("sha256").update(bytes).digest("hex")}  generated/${name}\n`,
    );
}
outputs.set("SHA256SUMS", Buffer.from(sums.join("")));
for (const relative of [
    "generated",
    ...[...outputs.keys()].map((name) => `generated/${name}`),
]) {
    if (
        fs
            .lstatSync(path.join(root, relative), { throwIfNoEntry: false })
            ?.isSymbolicLink()
    ) {
        throw new Error(`Symlink output: ${relative}`);
    }
}
for (const [name, bytes] of outputs) {
    const output = path.join(root, "generated", name);
    if (values.check) {
        if (!fs.existsSync(output) || !fs.readFileSync(output).equals(bytes)) {
            throw new Error(`Stale generated file: ${output}`);
        }
        continue;
    }
    fs.mkdirSync(path.join(root, "generated"), { recursive: true });
    fs.writeFileSync(output, bytes);
}

function sbom(arch) {
    const selected = components.filter((component) =>
        component.architectures.includes(arch),
    );
    const runtime = selected.filter(
        (component) => component.distribution !== "build-input",
    );
    const buildInputs = selected.filter(
        (component) => component.distribution === "build-input",
    );
    return {
        bomFormat: "CycloneDX",
        specVersion: "1.6",
        version: 1,
        components: runtime.map((component) =>
            sbomComponent({ component, arch }),
        ),
        formulation: [
            {
                components: buildInputs.map((component) =>
                    sbomComponent({ component, arch }),
                ),
            },
        ],
    };
}

function sbomComponent({ component, arch }) {
    const noticeOnly = component.revisionEvidence === "notice-only";
    const result = {
        type: "library",
        "bom-ref": `streamarr:ffmpeg:${arch}:${component.id}`,
        name: component.id,
        description: component.role,
        licenses: [
            {
                expression: component.licenseExpression,
                acknowledgement: "declared",
            },
        ],
        externalReferences: [
            { type: "vcs", url: component.repository },
            ...component.notices.map((notice) => ({
                type: "license",
                url: notice.url,
                comment: `Verbatim notice ${notice.sha256} in THIRD-PARTY-NOTICES.txt`,
            })),
        ],
        properties: [
            { name: "streamarr:distribution", value: component.distribution },
            {
                name: noticeOnly
                    ? "streamarr:notice-source-revision"
                    : "streamarr:source-revision",
                value: component.revision,
            },
            ...["recipe", "version_note"]
                .filter((key) => component[key])
                .map((key) => ({
                    name: `streamarr:${key.replaceAll("_", "-")}`,
                    value: component[key],
                })),
        ],
    };
    if (component.id !== "ffmpeg") return result;
    const purl = `pkg:github/jellyfin/jellyfin-ffmpeg@${lock.release}`;
    return {
        ...result,
        type: "application",
        name: "FFmpeg",
        version: lock.version,
        publisher: "Jellyfin",
        purl,
        "bom-ref": purl,
        hashes: [{ alg: "SHA-256", content: lock[`${arch}_sha256`] }],
        externalReferences: [
            ...result.externalReferences,
            {
                type: "distribution",
                url: `https://github.com/jellyfin/jellyfin-ffmpeg/releases/download/${lock.release}/${lock[`${arch}_asset`]}`,
            },
        ],
    };
}

function validateComponent(component) {
    if (!/^[a-z0-9-]+$/.test(component.id))
        throw new Error("Invalid component id");
    for (const field of [
        "repository",
        "revision",
        "role",
        "licenseExpression",
    ]) {
        if (typeof component[field] !== "string" || !component[field].trim())
            throw new Error(`Missing ${field}: ${component.id}`);
    }
    if (
        !["runtime", "embedded", "build-input"].includes(component.distribution)
    )
        throw new Error(`Invalid distribution: ${component.id}`);
    if (!["source", "notice-only"].includes(component.revisionEvidence))
        throw new Error(`Invalid revision evidence: ${component.id}`);
    if (
        !Array.isArray(component.architectures) ||
        !component.architectures.length ||
        component.architectures.some(
            (arch) => !["amd64", "arm64"].includes(arch),
        )
    ) {
        throw new Error(`Invalid architectures: ${component.id}`);
    }
    if (!Array.isArray(component.notices) || !component.notices.length)
        throw new Error(`Missing notices: ${component.id}`);
}

function readInput(relative) {
    if (
        !/^[a-zA-Z0-9_+./-]+$/.test(relative) ||
        relative.split("/").some((part) => ["", ".", ".."].includes(part))
    ) {
        throw new Error(`Unsafe input path: ${relative}`);
    }
    let current = root;
    for (const part of relative.split("/")) {
        current = path.join(current, part);
        if (fs.lstatSync(current).isSymbolicLink())
            throw new Error(`Symlink input: ${relative}`);
    }
    if (!fs.statSync(current).isFile())
        throw new Error(`Expected regular input file: ${relative}`);
    return fs.readFileSync(current);
}

function reportChanges(previous, current) {
    const before = new Map(
        previous.components.map((component) => [component.id, component]),
    );
    const after = new Map(
        current.components.map((component) => [component.id, component]),
    );
    for (const id of [...new Set([...before.keys(), ...after.keys()])].sort()) {
        if (!before.has(id)) {
            console.log(`Added component: ${id}`);
            continue;
        }
        if (!after.has(id)) {
            console.log(`Removed component: ${id}`);
            continue;
        }
        const fields = changedFields(before.get(id), after.get(id));
        if (fields.length)
            console.log(`Changed component: ${id} (${fields.join(", ")})`);
    }
    for (const field of changedFields(previous.lock, current.lock))
        console.log(`Changed lock: ${field}`);
    for (const file of changedFields(previous.inputs, current.inputs))
        console.log(`Changed input: ${file}`);
}

function changedFields(before, after) {
    return [...new Set([...Object.keys(before), ...Object.keys(after)])]
        .sort()
        .filter(
            (key) => JSON.stringify(before[key]) !== JSON.stringify(after[key]),
        );
}
