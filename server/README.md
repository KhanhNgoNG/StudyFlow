# StudyFlow Server

Backend của StudyFlow sử dụng Node.js, JavaScript ES modules, Express 5 và SQLite qua `better-sqlite3`. Hiện có nền tảng database, migration, seed, kiểm thử database và endpoint health. Hợp đồng API dùng chung với Android nằm trong [giao thức đồng bộ](../contracts/sync-protocol.md) và [OpenAPI](../contracts/openapi.yaml). Xem [hướng dẫn Bước 4](../docs/backend-step-4.md) để thực hiện từng lệnh và hiểu thiết kế.

## Yêu cầu môi trường

- Node.js `>=22.14.0 <23`, theo `package.json`.
- npm đi kèm Node.js để cài dependency từ `package-lock.json`.

Kiểm tra phiên bản:

```bash
node --version
npm --version
```

## Cài đặt

Từ thư mục gốc repository:

```bash
cd server
npm ci
```

Khi thiết lập lần đầu, tạo cấu hình local:

```bash
cp .env.example .env
```

Nếu đã có `.env`, giữ cấu hình hiện tại và chỉ bổ sung biến còn thiếu từ `.env.example`. Các lệnh chạy bên dưới được thực hiện trong thư mục `server/`.

## Cấu hình

| Biến | Giá trị mẫu | Ý nghĩa |
|---|---|---|
| `NODE_ENV` | `development` | Tên môi trường; reset chỉ được phép ở `development` hoặc `test`. |
| `HOST` | `0.0.0.0` | Lắng nghe trên các giao diện mạng của máy; dùng `127.0.0.1` nếu chỉ cần truy cập từ máy đó. |
| `PORT` | `3000` | Cổng HTTP, phải là số nguyên từ 1 đến 65535. |
| `DATABASE_PATH` | `./storage/studyflow.db` | File SQLite thực tế. Đường dẫn tương đối được tính từ thư mục `server/`, không phụ thuộc thư mục gọi Node. |

Script chạy dùng `--env-file=.env` của Node.js để đọc cấu hình trước khi nạp ứng dụng. File `.env` phải tồn tại; biến đã được đặt trong môi trường shell được ưu tiên hơn giá trị trong file. Sau khi thay `.env`, dừng và khởi động lại server.

## Chạy server

Tạo schema trước lần khởi động đầu tiên, sau đó nạp bộ dữ liệu demo nếu cần:

```bash
npm run db:migrate
npm run db:seed
npm run db:inspect
```

Migration có thể chạy lại; file migration đã chạy được kiểm tra checksum. Seed chỉ chèn vào database rỗng; nếu đủ các ID seed đã tồn tại thì bỏ qua, không ghi đè sửa đổi mới. Database có dữ liệu khác hoặc thiếu một phần bộ seed sẽ bị từ chối seed. Server không tự migrate hoặc seed khi khởi động.

Chế độ phát triển, tự khởi động lại khi file JavaScript đang được sử dụng thay đổi:

```bash
npm run dev
```

Chạy thông thường:

```bash
npm start
```

Với cấu hình mẫu, server lắng nghe ở cổng `3000`. Nhấn `Ctrl+C` trong terminal đang chạy để dừng server.

## Kiểm tra kết nối

Giữ server chạy và mở terminal thứ hai:

```bash
curl -i http://localhost:3000/api/v1/health
```

Kết quả mong đợi là HTTP `200 OK` với body rỗng. Server kiểm tra hai bảng nghiệp vụ trước khi mở port; health kiểm tra khả năng truy vấn hai bảng trên connection đang dùng, trả `503` nếu thất bại. Health không chứng minh toàn bộ CRUD hoặc đồng bộ đã hoạt động.

Nếu đổi `PORT`, thay cổng tương ứng trong URL.

## Cấu trúc mã hiện tại

- `src/app.js`: export `createApp()`, cấu hình Express, đọc JSON và khai báo health; không tự mở port.
- `src/server.js`: tạo app, mở port và xử lý lỗi khởi động.
- `src/config/env.js`: đọc cấu hình và kiểm tra `PORT`.
- `src/database/connection.js`: mở SQLite, bật foreign key, WAL, timeout khóa và synchronous FULL.
- `src/database/migrate.js`, `migrations/001_initial.sql`: tạo schema và theo dõi migration.
- `src/database/seed.js`, `seeds/demo-tasks.json`: nạp dữ liệu mẫu cố định và reset có xác nhận.
- `src/database/cli.js`: các lệnh migrate, seed, inspect và reset.
- `tests/database.test.js`: kiểm thử bằng database tạm riêng, không dùng dữ liệu demo.
- `.env.example`: cấu hình mẫu được lưu trong Git.
- `package-lock.json`: khóa phiên bản dependency để cài lại bằng `npm ci`.

## Giới hạn hiện tại

- Chưa triển khai API công việc: danh sách, chi tiết, tạo, cập nhật và xóa logic.
- Database có ràng buộc cơ bản và cột version/tombstone, nhưng chưa có validation HTTP, service kiểm tra version hoặc xử lý xóa qua API.
- Đã có bảng receipt; chưa có logic tạo hash request, tìm receipt trước version hoặc replay response qua API.
- Chưa có middleware lỗi chung theo hợp đồng; lỗi route và JSON hiện theo hành vi mặc định của Express.
- Test hiện kiểm tra nền tảng database; chưa chứng minh tính đúng đắn toàn bộ API hoặc đồng bộ Android.

Các endpoint và schema trong OpenAPI mô tả hợp đồng cần triển khai; không có nghĩa mọi endpoint đã hoạt động.

## Kiểm thử database

```bash
npm test
```

Test tạo database ở thư mục tạm rồi dọn sau khi chạy. Bao gồm migration/seed chạy lại, ràng buộc, ID duy nhất, rollback task và receipt, khóa giữa hai connection và dữ liệu tồn tại sau khi tiến trình khác mở lại file.

## Reset dữ liệu demo

Chỉ chạy sau khi dừng server và thống nhất reset cùng hai thiết bị. Lệnh sau xóa toàn bộ task và receipt trong database cấu hình, giữ schema và lịch sử migration:

```bash
npm run db:reset -- --confirm-reset
```

Lệnh bị từ chối nếu thiếu cờ xác nhận hoặc `NODE_ENV` không phải `development`/`test`. Sau reset, chạy `npm run db:seed` nếu muốn nạp lại bộ demo. Không xóa riêng receipt hoặc tombstone trong đợt kiểm thử vì sẽ phá giả định retry/đồng bộ.

## Xử lý lỗi khởi động

- Thiếu `.env`: tạo file từ `.env.example` theo hướng dẫn cài đặt.
- `PORT` không hợp lệ: sửa thành số nguyên từ 1 đến 65535 rồi chạy lại.
- Cổng đang được sử dụng (`EADDRINUSE`): dừng tiến trình server cũ hoặc chọn cổng khác trong `.env`.
- Không tìm thấy Express: chạy `npm ci` trong `server/` và kiểm tra lại phiên bản Node.js.
- Thiếu database hoặc schema: kiểm tra `DATABASE_PATH`, chạy `npm run db:migrate` rồi khởi động lại.
- Migration đã chạy bị sửa: khôi phục nội dung migration gốc; thay đổi schema bằng file migration mới.
- Lỗi native module `better-sqlite3`: kiểm tra phiên bản Node.js và chạy lại `npm ci` trên máy đang sử dụng, không sao chép `node_modules` từ máy khác.

## Quy tắc lưu Git

Lưu mã nguồn, README, `.env.example`, `package.json` và `package-lock.json`. Không lưu `.env`, `node_modules/`, database runtime trong `storage/` hoặc log cá nhân; các đường dẫn này đã được loại trừ trong `.gitignore` của repo.
