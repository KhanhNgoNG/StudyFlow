import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { connect } from 'node:net';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { faultScenarioNames, runFaultScenarios } from '../test-support/fault-harness.js';

const cleanupKeys = ['childrenStopped', 'proxyClosed', 'connectionsClosed', 'directoryRemoved'];

function assertCleanup(cleanup) {
  assert.deepEqual(Object.keys(cleanup).sort(), [...cleanupKeys].sort());
  for (const key of cleanupKeys) assert.equal(cleanup[key], true, key);
}

test('công cụ tái hiện đủ năm lỗi với bằng chứng SQLite/response và dọn tài nguyên', { timeout: 60000 }, async () => {
  const events = [];
  const result = await runFaultScenarios({ report: (event) => events.push(event) });
  assert.deepEqual(result.scenarios.map((scenario) => scenario.name), faultScenarioNames);
  for (const scenario of result.scenarios) {
    assert.equal(scenario.status, 'pass', scenario.name);
    assert.match(scenario.mutationId, /^[0-9a-f-]{36}$/i);
    assert.ok(scenario.attempts.length >= 2, scenario.name);
    assert.ok(scenario.attempts.some((attempt) => attempt.clientError || attempt.httpStatus === 500));
    assert.equal(scenario.after.tasks, scenario.before.tasks + 1, scenario.name);
    assert.equal(scenario.after.receipts, scenario.before.receipts + (scenario.name === 'lost-response' ? 2 : 1));
    assert.equal(scenario.attempts[0].mutationId, scenario.mutationId);
    assert.equal(scenario.attempts.at(-1).mutationId, scenario.mutationId);
    assert.equal(scenario.retry.httpStatus, 201);
    assert.equal(scenario.retry.responseVersion, 1);
    assert.equal(scenario.retry.currentVersion, scenario.name === 'lost-response' ? 2 : 1);
    const replayed = ['unavailable', 'timeout-after-commit', 'lost-response'].includes(scenario.name);
    assert.equal(scenario.retry.outcome, replayed ? 'replay' : 'applied');
    if (replayed) assert.equal(scenario.retry.receiptCountUnchanged, true);
  }
  const byName = Object.fromEntries(result.scenarios.map((scenario) => [scenario.name, scenario]));
  assert.equal(byName.unavailable.attempts[1].clientError, 'ECONNREFUSED');
  assert.equal(byName['timeout-before-commit'].attempts[0].clientError, 'CLIENT_TIMEOUT');
  assert.equal(byName['timeout-before-commit'].attempts[0].requestId, null);
  assert.equal(byName['transient-before-commit'].attempts[0].httpStatus, 500);
  assert.equal(byName['timeout-after-commit'].attempts[0].clientError, 'CLIENT_TIMEOUT');
  assert.equal(byName['lost-response'].attempts[0].httpStatus, null);
  assert.ok(events.some((event) => event.source === 'backend' && event.event === 'mutation.completed'));
  assert.ok(events.length > 0, 'The CLI must have observable evidence');
  assertCleanup(result.cleanup);
  assert.doesNotMatch(JSON.stringify(events), /Hoàn thiện các màn hình chính|Kiểm thử lỗi có kiểm soát|Thay đổi mới sau response bị mất/);
});

test('harness lỗi khi proxy đang giữ response vẫn đóng socket/child và xóa DB tạm', { timeout: 20000 }, async () => {
  let owned;
  await assert.rejects(runFaultScenarios({
    scenario: 'timeout-after-commit',
    onCheckpoint(checkpoint) {
      if (checkpoint.phase === 'upstream-held') {
        owned = checkpoint;
        throw new Error('Intentional harness assertion failure');
      }
    },
  }), (error) => {
    assert.equal(error.message, 'Intentional harness assertion failure');
    assertCleanup(error.cleanup);
    return true;
  });
  assert.ok(owned?.childPid);
  assert.equal(existsSync(owned.directory), false);
  assert.throws(() => process.kill(owned.childPid, 0), (error) => error.code === 'ESRCH');
  assert.equal(owned.ports.length, 2);
  for (const port of owned.ports) {
    await new Promise((resolve, reject) => {
      const socket = connect({ host: '127.0.0.1', port });
      const timer = setTimeout(() => { socket.destroy(); reject(new Error('Cleanup port check timed out')); }, 1000);
      socket.once('connect', () => { clearTimeout(timer); socket.destroy(); reject(new Error('Owned listener survived cleanup')); });
      socket.once('error', (error) => {
        clearTimeout(timer);
        if (error.code === 'ECONNREFUSED') resolve(); else reject(error);
      });
    });
  }
});

async function cli(args) {
  const child = spawn(process.execPath, [fileURLToPath(new URL('../scripts/reproduce-faults.js', import.meta.url)), ...args], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', (chunk) => { stdout += chunk; });
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  return new Promise((resolve, reject) => {
    const deadline = setTimeout(() => child.kill('SIGKILL'), 5000);
    child.once('error', (error) => { clearTimeout(deadline); reject(error); });
    child.once('close', (code, signal) => { clearTimeout(deadline); resolve({ code, signal, stdout, stderr }); });
  });
}

test('CLI có help, từ chối scenario sai; start/dev chỉ trỏ tới production', { timeout: 15000 }, async () => {
  const help = await cli(['--help']);
  assert.equal(help.code, 0);
  assert.equal(help.signal, null);
  assert.match(help.stdout, /scenario|Usage|Cách chạy/i);
  const invalid = await cli(['--scenario=not-a-scenario']);
  assert.equal(invalid.code, 1);
  assert.equal(invalid.signal, null);
  const failure = JSON.parse(invalid.stdout.trim());
  assert.equal(failure.event, 'run.failed');
  assert.equal(failure.level, 'error');
  assert.match(failure.reason, /Invalid arguments/);
  const config = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
  assert.equal(config.scripts.start, 'node --env-file=.env src/server.js');
  assert.equal(config.scripts.dev, 'node --watch --env-file=.env src/server.js');
  function inspect(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = new URL(entry.name + (entry.isDirectory() ? '/' : ''), directory);
      if (entry.isDirectory()) inspect(path);
      else if (entry.name.endsWith('.js')) {
        assert.doesNotMatch(readFileSync(path, 'utf8'), /(?:from\s*|import\s*\()['"][^'"]*(?:test-support|reproduce-faults|fault-harness)/);
      }
    }
  }
  inspect(new URL('../src/', import.meta.url));
});
