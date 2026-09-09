import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { Script } from "node:vm";

const request = readFileSync(new URL("../GraphQL/Get Me.bru", import.meta.url), "utf8");
const source = request.match(/^script:post-response \{\n([\s\S]*?)^\}/m)?.[1];
assert.ok(source, "Get Me must have a post-response script");
const script = new Script(source);

function selectedProfile(profiles, profile = profiles.find((candidate) => candidate.selected) ?? null) {
  const variables = new Map([["PROFILE_ID", "stale-profile"]]);
  script.runInNewContext({
    res: {
      status: 200,
      body: {
        data: {
          me: {
            contextHousehold: { id: "household" },
            selectedProfile: profile,
            selectableProfiles: { edges: profiles.map((node) => ({ node })) },
          },
        },
      },
    },
    bru: { setVar: (name, value) => variables.set(name, value) },
  });
  assert.equal(variables.get("HOUSEHOLD_ID"), "household");
  return variables.get("PROFILE_ID");
}

test("Should skip a PIN-protected Profile when another Profile needs no PIN", () => {
  assert.equal(
    selectedProfile([
      { id: "pinned", selected: false, locked: false, pinConfigured: true },
      { id: "usable", selected: false, locked: false, pinConfigured: false },
    ]),
    "usable",
  );
});

test("Should clear the previous Profile when every Profile needs a PIN or is locked", () => {
  assert.equal(
    selectedProfile([
      { id: "pinned", selected: true, locked: false, pinConfigured: true },
      { id: "locked", selected: false, locked: true, pinConfigured: false },
    ]),
    "",
  );
});

test("Should prefer the selected Profile when it is usable without a PIN", () => {
  assert.equal(
    selectedProfile([
      { id: "first", selected: false, locked: false, pinConfigured: false },
      { id: "selected", selected: true, locked: false, pinConfigured: false },
    ]),
    "selected",
  );
});

test("Should prefer the selected Profile when it is outside the first picker page", () => {
  const profiles = Array.from({ length: 100 }, (_, index) => ({
    id: `profile-${index}`,
    selected: false,
    locked: false,
    pinConfigured: false,
  }));

  assert.equal(
    selectedProfile(profiles, {
      id: "selected-outside-page",
      selected: true,
      locked: false,
      pinConfigured: false,
    }),
    "selected-outside-page",
  );
});

for (const protection of [
  { locked: false, pinConfigured: true },
  { locked: true, pinConfigured: false },
]) {
  const reason = protection.locked ? "locked" : "PIN-protected";
  test(`Should skip the selected Profile when it is ${reason}`, () => {
    assert.equal(
      selectedProfile([
        { id: "selected", selected: true, ...protection },
        { id: "usable", selected: false, locked: false, pinConfigured: false },
      ]),
      "usable",
    );
  });

  test(`Should use the first eligible Profile when the off-page selection is ${reason}`, () => {
    assert.equal(
      selectedProfile(
        [{ id: "usable", selected: false, locked: false, pinConfigured: false }],
        { id: "selected-outside-page", selected: true, ...protection },
      ),
      "usable",
    );
  });

  test(`Should clear the previous Profile when the off-page selection is ${reason} and no fallback is eligible`, () => {
    assert.equal(
      selectedProfile(
        [{ id: "locked", selected: false, locked: true, pinConfigured: false }],
        { id: "selected-outside-page", selected: true, ...protection },
      ),
      "",
    );
  });
}

test("Should use the selected Profile when the picker page is empty", () => {
  assert.equal(
    selectedProfile([], {
      id: "selected-outside-page",
      selected: true,
      locked: false,
      pinConfigured: false,
    }),
    "selected-outside-page",
  );
});

test("Should clear the previous Profile when the picker is empty", () => {
  assert.equal(selectedProfile([]), "");
});
