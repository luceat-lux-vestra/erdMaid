"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { compilePolicy, expectedType, reconcileIssue } = require("./issue-metadata.cjs");

const root = path.join(__dirname, "..");
const policy = JSON.parse(fs.readFileSync(path.join(root, ".github", "issue-metadata-policy.json"), "utf8"));
const repositoryPolicy = JSON.parse(fs.readFileSync(path.join(root, ".github", "repository-policy.json"), "utf8"));
const canonical = new Set(repositoryPolicy.labels.map((entry) => entry.name));
const { managed, rules } = compilePolicy(policy);

test("managed types are owned by the canonical repository label catalog", () => {
  for (const label of managed) assert.ok(canonical.has(label), label);
});

test("canonical prefixes map deterministically", () => {
  const cases = new Map([
    ["bug(metadata): wrong relation", "type:bug"],
    ["fix(renderer): escaping", "type:bug"],
    ["feat(export): add option", "type:feature"],
    ["task(proof): reconcile docs", "type:maintenance"],
    ["security: tighten workflow", "type:maintenance"],
    ["hardening(reassessment): refresh controls", "type:maintenance"],
    ["Investigate DataGrip behavior", null]
  ]);
  for (const [title, expected] of cases) assert.equal(expectedType(title, rules), expected, title);
});

test("explicit title repairs only conflicting managed type labels", () => {
  assert.deepEqual(reconcileIssue({
    title: "feat(export): add option",
    labels: ["type:maintenance", "area:metadata"]
  }, policy), {
    expectedType: "type:feature",
    add: ["type:feature"],
    remove: ["type:maintenance"],
    diagnostics: []
  });
});

test("unknown title preserves a single maintainer-selected type", () => {
  assert.deepEqual(reconcileIssue({
    title: "Investigate DataGrip behavior",
    labels: ["type:maintenance", "area:compatibility"]
  }, policy), {
    expectedType: null,
    add: [],
    remove: [],
    diagnostics: []
  });
});

test("unknown untyped title is diagnostic only", () => {
  assert.deepEqual(reconcileIssue({
    title: "Investigate DataGrip behavior",
    labels: ["area:compatibility"]
  }, policy), {
    expectedType: null,
    add: [],
    remove: [],
    diagnostics: ["unclassified-title"]
  });
});

test("ambiguous managed types without explicit authority fail closed", () => {
  assert.deepEqual(reconcileIssue({
    title: "Investigate DataGrip behavior",
    labels: ["type:bug", "type:maintenance", "area:compatibility"]
  }, policy), {
    expectedType: null,
    add: [],
    remove: [],
    diagnostics: ["ambiguous-managed-type"]
  });
});
