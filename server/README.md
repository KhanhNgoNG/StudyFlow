# StudyFlow Server

Bộ khung backend Node.js. Chưa chọn framework, driver cơ sở dữ liệu hoặc triển khai API.

- `src/app.js`: cấu hình ứng dụng.
- `src/server.js`: khởi động HTTP server.
- `src/database/`: migrations và seeds.
- `tests/`: kiểm thử tích hợp và fixtures.
- `storage/`: cơ sở dữ liệu runtime, không lưu trong Git.

Sao chép `.env.example` thành `.env` khi bắt đầu triển khai. `package.json` và lockfile hiện chưa có dependency hoặc script chạy ứng dụng. Xem `../docs/setup.md` và `../contracts/`.
