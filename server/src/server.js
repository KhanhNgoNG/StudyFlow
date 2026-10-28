import { createApp } from './app.js';
import { config } from './config/env.js';

const app = createApp();

const server = app.listen(config.port, config.host, () => {
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
});