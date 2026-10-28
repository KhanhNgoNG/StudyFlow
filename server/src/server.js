import { createApp } from './app.js';
import { config } from './config/env.js';
import { openDatabase } from './database/connection.js';

let database;
try {
  database = openDatabase(config.databasePath, { fileMustExist: true });
  database.prepare('SELECT id FROM tasks LIMIT 0').all();
  database.prepare('SELECT mutation_id FROM mutation_receipts LIMIT 0').all();
} catch (error) {
  if (database?.open) database.close();
  console.error('Không mở được schema database. Chạy npm run db:migrate trước khi khởi động.');
  console.error(error.message);
  process.exit(1);
}

const app = createApp({ database });

const server = app.listen(config.port, config.host);

server.once('listening', () => {
  console.log(
    `StudyFlow server đang chạy tại cổng ${config.port}`,
  );
});

server.on('error', (error) => {
  if (error.code === 'EADDRINUSE') {
    console.error(`Cổng ${config.port} đang được sử dụng.`);
  } else {
    console.error('Không thể khởi động StudyFlow server.');
    console.error(error.message);
  }

  process.exitCode = 1;
  if (database.open) database.close();
});

function shutdown() {
  server.close(() => {
    if (database.open) database.close();
  });
}

process.once('SIGINT', shutdown);
process.once('SIGTERM', shutdown);
