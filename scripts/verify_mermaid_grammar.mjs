#!/usr/bin/env node

import { readFile, readdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const EXPECTED_PACKAGE = 'mermaid';
const EXPECTED_VERSION = '11.17.2';
const EXPECTED_FIXTURES = [
  'grammar-empty-role-boundary.mmd',
  'production-hostile.mmd',
  'production-surface.mmd',
];

function fail(message) {
  throw new Error(`MERMAID GRAMMAR EVIDENCE ERROR: ${message}`);
}

if (process.argv.length !== 4) {
  fail('usage: verify_mermaid_grammar.mjs <extracted-package-root> <fixture-root>');
}

const packageRoot = resolve(process.argv[2]);
const fixtureRoot = resolve(process.argv[3]);
const packageJson = JSON.parse(await readFile(join(packageRoot, 'package.json'), 'utf8'));

if (packageJson.name !== EXPECTED_PACKAGE) {
  fail(`expected package ${EXPECTED_PACKAGE}, observed ${String(packageJson.name)}`);
}
if (packageJson.version !== EXPECTED_VERSION) {
  fail(`expected Mermaid version ${EXPECTED_VERSION}, observed ${String(packageJson.version)}`);
}

const actualFixtures = (await readdir(fixtureRoot))
  .filter((name) => name.endsWith('.mmd'))
  .sort();
if (JSON.stringify(actualFixtures) !== JSON.stringify(EXPECTED_FIXTURES)) {
  fail(
    `fixture manifest drift: expected ${EXPECTED_FIXTURES.join(', ')}, observed ${actualFixtures.join(', ')}`
  );
}

const bundlePath = join(packageRoot, 'dist', 'mermaid.esm.min.mjs');
const imported = await import(pathToFileURL(bundlePath).href);
const mermaid = imported.default;
if (!mermaid || typeof mermaid.parse !== 'function') {
  fail('pinned Mermaid bundle did not expose its public parse() API');
}

for (const fixture of EXPECTED_FIXTURES) {
  const text = await readFile(join(fixtureRoot, fixture), 'utf8');
  const parsed = await mermaid.parse(text, { suppressErrors: false });
  if (!parsed || parsed.diagramType !== 'er') {
    fail(`${fixture} parsed without the expected er diagram type`);
  }
  console.log(`PASS ${fixture}: diagramType=${parsed.diagramType}`);
}

const invalidControl = 'erDiagram\n    A ||-- B : "invalid"\n';
const invalidResult = await mermaid.parse(invalidControl, { suppressErrors: true });
if (invalidResult !== false) {
  fail('deliberately invalid ER negative control was accepted');
}

console.log(`PASS negative control: invalid ER syntax rejected by Mermaid ${EXPECTED_VERSION}`);
console.log(`Mermaid grammar evidence complete: ${EXPECTED_FIXTURES.length} positive fixture(s)`);
