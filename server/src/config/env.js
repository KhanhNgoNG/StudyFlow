const portText = process.env.PORT ?? '3000';

if (!/^\d+$/.test(portText)) {
  throw new Error('PORT phải là số nguyên từ 1 đến 65535.');
}

const port = Number(portText);

if (port < 1 || port > 65535) {
  throw new Error('PORT phải nằm trong khoảng 1 đến 65535.');
}

export const config = Object.freeze({
  nodeEnv: process.env.NODE_ENV ?? 'development',
  host: process.env.HOST ?? '0.0.0.0',
  port,
  databasePath:
    process.env.DATABASE_PATH ?? './storage/studyflow.db',
});