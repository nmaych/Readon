/**
 * Parse-check the repository's GitHub YAML.
 *
 * GitHub validates workflow syntax server-side, and a syntax error there shows up
 * as a red X on a push with a message that is easy to misread. This parses the
 * same files locally so the mistake surfaces before the push.
 *
 * Structural checks on top of parsing, because valid YAML can still be a
 * workflow that never runs or a job that can never succeed:
 *
 *   - a workflow must declare a trigger. `on:` is YAML 1.1 boolean `true`, so a
 *     naive key check misses it entirely.
 *   - every `uses:` should be a pinned major version, not a branch.
 *   - `runs-on` must be present for every job.
 *
 *   node tools/check_workflows.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);

// js-yaml lives in the toolchain's node_modules, not in this repository.
const YAML_PATHS = [
  process.env.READON_JS_YAML,
  join(ROOT, 'node_modules', 'js-yaml'),
].filter(Boolean);

let yaml = null;
for (const p of YAML_PATHS) {
  try {
    yaml = require(p);
    break;
  } catch {
    /* try the next candidate */
  }
}

if (!yaml) {
  console.error('js-yaml not found. Set READON_JS_YAML to its directory.');
  process.exit(2);
}

const failures = [];
const warnings = [];

function fail(msg) {
  failures.push(msg);
}

function ok(msg) {
  console.log(`  [ok]   ${msg}`);
}

function warn(msg) {
  warnings.push(msg);
}

function load(path) {
  try {
    return yaml.load(readFileSync(path, 'utf8'));
  } catch (e) {
    fail(`${relative(ROOT, path)}: ${e.message.split('\n')[0]}`);
    return null;
  }
}

// --- workflows --------------------------------------------------------------
const wfDir = join(ROOT, '.github', 'workflows');
const workflows = readdirSync(wfDir).filter((f) => /\.ya?ml$/.test(f)).sort();

if (workflows.length === 0) fail('.github/workflows contains no workflow files');

for (const file of workflows) {
  const path = join(wfDir, file);
  const doc = load(path);
  if (!doc) continue;

  const rel = `.github/workflows/${file}`;

  // `on:` is parsed as boolean true by YAML 1.1, so both spellings are checked.
  const trigger = doc.on ?? doc[true];
  if (!trigger) {
    fail(`${rel}: no trigger (no 'on:')`);
  }

  const jobs = doc.jobs;
  if (!jobs || typeof jobs !== 'object') {
    fail(`${rel}: no jobs`);
    continue;
  }

  for (const [name, job] of Object.entries(jobs)) {
    if (!job || typeof job !== 'object') {
      fail(`${rel}: job '${name}' is not a mapping`);
      continue;
    }
    if (!job['runs-on']) fail(`${rel}: job '${name}' has no runs-on`);

    const steps = job.steps;
    if (!Array.isArray(steps) || steps.length === 0) {
      fail(`${rel}: job '${name}' has no steps`);
      continue;
    }
    steps.forEach((step, i) => {
      if (!step || typeof step !== 'object') {
        fail(`${rel}: job '${name}' step ${i + 1} is not a mapping`);
        return;
      }
      if (!step.uses && !step.run) {
        fail(`${rel}: job '${name}' step ${i + 1} has neither uses nor run`);
      }
      // A floating action reference can change under us between runs.
      if (step.uses && !/@v?\d/.test(step.uses) && !step.uses.startsWith('./')) {
        warn(`${rel}: '${step.uses}' is not pinned to a major version`);
      }
    });
  }

  const triggers = trigger === true ? 'on: (scalar)' : Object.keys(trigger).join(', ');
  ok(`${rel}: ${Object.keys(jobs).length} job(s), triggers: ${triggers}`);
}

// --- dependabot and issue templates ----------------------------------------
for (const rel of ['dependabot.yml']) {
  const path = join(ROOT, '.github', rel);
  if (load(path)) ok(`.github/${rel} parses`);
}

const tplDir = join(ROOT, '.github', 'ISSUE_TEMPLATE');
for (const file of readdirSync(tplDir).filter((f) => /\.ya?ml$/.test(f)).sort()) {
  if (load(join(tplDir, file))) ok(`.github/ISSUE_TEMPLATE/${file} parses`);
}

// --- report -----------------------------------------------------------------
console.log();
for (const w of warnings) console.log(`  [warn] ${w}`);
if (failures.length) {
  for (const f of failures) console.log(`  [FAIL] ${f}`);
  console.log(`\n${failures.length} problem(s)`);
  process.exit(1);
}
console.log('workflow configuration OK');
