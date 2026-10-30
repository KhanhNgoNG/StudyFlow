// Test-only process boundary. The real app/service/repositories handle every
// request; IPC only reports the dynamic port and shuts down this owned server.
import { createApp } from '../src/app.js';
import { openDatabase } from '../src/database/connection.js';

let database;
let server;
let closing = false;

function closeDatabase() {
  if (database?.open) database.close();
  if (process.connected) process.disconnect();
}

function shutdown() {
  if (closing) return;
  closing = true;
  if (server) {
    server.close(closeDatabase);
    server.closeAllConnections();
  } else closeDatabase();
}

process.once('SIGTERM', shutdown);
process.once('SIGINT', shutdown);
process.once('disconnect', shutdown);
process.on('message', (message) => {
  if (message?.event === 'shutdown') shutdown();
});

try {
  database = openDatabase(process.argv[2], { fileMustExist: true });
  database.prepare('SELECT id FROM tasks LIMIT 0').all();
  database.prepare('SELECT mutation_id FROM mutation_receipts LIMIT 0').all();
  server = createApp({ database }).listen(0, '127.0.0.1');
  server.once('listening', () => {
    if (closing) {
      server.close(closeDatabase);
      server.closeAllConnections();
      return;
    }
    if (process.connected) process.send({ event: 'ready', port: server.address().port });
    else shutdown();
  });
  server.once('error', (error) => {
    console.error(error);
    process.exitCode = 1;
    shutdown();
  });
} catch (error) {
  console.error(error);
  process.exitCode = 1;
  shutdown();
}
