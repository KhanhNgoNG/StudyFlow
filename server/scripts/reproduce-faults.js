import { faultScenarioNames, runFaultScenarios } from '../test-support/fault-harness.js';

function output(record) {
  process.stdout.write(`${JSON.stringify(record)}\n`);
}

const args = process.argv.slice(2);
if (args.length === 1 && args[0] === '--help') {
  console.log('Run all scenarios: npm run test:faults');
  console.log(`Run one: npm run test:faults -- --scenario=${faultScenarioNames[0]}`);
  console.log(`Scenarios: ${faultScenarioNames.join(', ')}`);
} else {
  const selection = args.length === 0 ? 'all' : args.length === 1 && args[0].startsWith('--scenario=') ? args[0].slice(11) : undefined;
  if (selection !== 'all' && !faultScenarioNames.includes(selection)) {
    output({ level: 'error', event: 'run.failed', reason: 'Invalid arguments; use --help' });
    process.exitCode = 1;
  } else {
    try {
      await runFaultScenarios({ scenario: selection, report: output });
    } catch (error) {
      output({ time: new Date().toISOString(), level: 'error', event: 'run.failed',
        reason: error?.code === 'ERR_ASSERTION' ? 'Scenario assertion failed' : 'Fault harness could not complete',
        errorName: ['Error', 'AssertionError', 'TypeError', 'RangeError'].includes(error?.name) ? error.name : 'Error',
        errorCode: /^[A-Z][A-Z0-9_]{0,63}$/.test(error?.code ?? '') ? error.code : undefined,
        scenario: faultScenarioNames.includes(error?.scenario) ? error.scenario : undefined,
        cleanup: error?.cleanup });
      process.exitCode = 1;
    }
  }
}
