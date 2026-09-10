const [, , tag, pomVersion] = process.argv;

if (!/^v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$/.test(tag)) {
  process.stderr.write('Release tag must be stable SemVer (vX.Y.Z)\n');
  process.exit(1);
}

if (tag !== `v${pomVersion}`) {
  process.stderr.write('Release tag and Maven version must agree\n');
  process.exit(1);
}

process.stdout.write(`${pomVersion}\n`);
