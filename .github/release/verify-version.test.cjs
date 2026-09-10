const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const { test } = require('node:test');

function verify(tag, version) {
  return spawnSync(process.execPath, [path.join(__dirname, 'verify-version.cjs'), tag, version], {
    encoding: 'utf8',
  });
}

test('accepts a release whose tag and Maven version agree', () => {
  const result = verify('v0.12.3', '0.12.3');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout.trim(), '0.12.3');
});

test('rejects drift between the tag and the Maven artifact', () => {
  const result = verify('v0.12.3', '0.0.1-SNAPSHOT');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /tag and Maven version must agree/);
});

for (const version of ['01.2.3', '1.2', '1.2.3-SNAPSHOT', '1.2.3-rc.1', '1.2.3+build', '1.2.3\nextra']) {
  test(`rejects a non-stable or malformed release: ${JSON.stringify(version)}`, () => {
    const result = verify(`v${version}`, version);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /stable SemVer/);
  });
}
