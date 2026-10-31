# Hướng dẫn Bước 4 của backend StudyFlow

> Tài liệu lịch sử mốc database Bước 4. Hiện đã có validation, API đọc/ghi và receipt ở Bước 5–10; xem [README server](../server/README.md) để chạy/bàn giao hiện tại. Các giới hạn “bước sau” dưới đây chỉ mô tả mốc Bước 4.

## Mục tiêu và trạng thái

Bước 4 tạo nền tảng database thật cho server: kết nối SQLite, schema tasks và mutation_receipts, migration, seed và kiểm thử tính bền vững/transaction. Mã tương ứng đã được triển khai trong repo; các lệnh dưới đây chạy trực tiếp, không phải đoạn minh họa cần tự hoàn thiện.

API CRUD, validation request và service chống áp dụng trùng vẫn thuộc các bước sau. Bước 3 đã có Express và health; middleware lỗi chung theo hợp đồng sẽ được hoàn thiện cùng phần validation, chưa có ở mốc hiện tại.

## 1 Kiểm tra và cài dependency

Mở terminal ở thư mục gốc StudyFlow, xem trạng thái trước khi làm:

```bash
git status -sb
cd server
node --version
npm --version
npm ci
```

Node phải phù hợp `>=22.14.0 <23`. `better-sqlite3` đã được thêm và khóa phiên bản trong package/lockfile; dùng `npm ci` để cài lại đúng bộ, không cần `npm init` hoặc cài SQLite toàn cục. Không sao chép node_modules từ máy khác vì driver có native module.

## 2 Kiểm tra cấu hình database

Tạo `.env` nếu thiếu bằng `if [ ! -f .env ]; then cp .env.example .env; fi`. Nếu đã có, giữ file và kiểm tra các giá trị:

```dotenv
NODE_ENV=development
HOST=0.0.0.0
PORT=3000
DATABASE_PATH=./storage/studyflow.db
```

`DATABASE_PATH` là file SQLite, không phải URL database hoặc địa chỉ server. Đường dẫn tương đối luôn tính từ `server/`. Mã `src/database/connection.js` tạo thư mục cha khi migration cần tạo database; không cần tự tạo file `.db` rỗng.

Connection bật foreign key để receipt không tham chiếu task vắng mặt, WAL cho phối hợp đọc/ghi và synchronous FULL cho độ bền commit theo bảo đảm của SQLite/hệ thống lưu trữ. Busy timeout 5 giây cho phép đợi khóa ngắn; không đồng nghĩa retry HTTP.

## 3 Đọc schema đã triển khai

Mở `server/src/database/migrations/001_initial.sql`. Đây là lệnh tạo hai bảng nghiệp vụ, không phải API.

`tasks` lưu UUID, nội dung, môn học, deadline, priority, completed, is_deleted, version và thời gian UTC. SQLite dùng 0/1 cho boolean; mapper ở bước API phải đổi thành true/false. Version bắt đầu từ 1; baseVersion chỉ nằm trong request nên không có cột tương ứng. Tombstone là hàng vẫn còn với is_deleted=1, không phải hàng bị xóa vật lý.

`mutation_receipts` lưu UUID mutation, task ID, method/path, hash request, status/body thành công và thời gian ghi nhận. ID mutation duy nhất giúp ngăn ghi hai receipt cùng ID. Hash sẽ do service ở bước retry tính từ toàn bộ request. Bước này mới tạo chỗ lưu, chưa xử lý HTTP retry.

Runner còn tạo `schema_migrations` để biết file nào đã áp dụng và checksum của file. Không thêm bảng tài khoản, môn học động hoặc outbox Android vào database server.

Xem chi tiết từng cột và mapping trong [thiết kế database](database-design.md).

## 4 Chạy migration

Trong thư mục `server/`:

```bash
npm run db:migrate
```

Lần đầu, lệnh tạo file database, ba bảng và ghi nhận `001_initial.sql`. Kết quả mong đợi có dòng `Đã áp dụng: 001_initial.sql`. Chạy lại:

```bash
npm run db:migrate
```

Kết quả mong đợi: `Schema đã cập nhật, không có migration mới.`

Mục đích là người lấy repo chỉ cần chạy lệnh để có cùng schema, không phải tự tạo bảng qua GUI. Runner bọc việc tạo bảng và ghi lịch sử trong transaction IMMEDIATE. Nếu SQL lỗi, các thay đổi trong lượt migration rollback. Nếu sửa nội dung file đã chạy, checksum không khớp và lệnh báo lỗi. Thay đổi schema sau này bằng file `002_...sql`, không sửa 001 trên database đã áp dụng.

## 5 Nạp bộ dữ liệu demo

```bash
npm run db:seed
```

Lần đầu trên database rỗng, kết quả mong đợi là `Đã thêm 3 task mẫu.` Dữ liệu nằm trong `src/database/seeds/demo-tasks.json`:

| Task | Nội dung cần kiểm tra |
|---|---|
| Hoàn thiện API StudyFlow | Tiếng Việt, chưa hoàn thành, deadline null, version 1. |
| Ôn tập transaction SQLite | Đã hoàn thành, deadline cụ thể, priority HIGH. |
| Công việc mẫu đã xóa | Tombstone, giữ nội dung và ID, version 2. |

UUID và timestamp cố định để các lần kiểm thử có cùng điểm xuất phát. Timestamp trong seed là dữ liệu minh họa; không phải bằng chứng một thao tác đã xảy ra ngoài thực tế. Không tạo receipt thành công giả cho seed vì seed không phải mutation HTTP.

Chạy lại `npm run db:seed`: nếu đủ các ID seed đã tồn tại thì báo bỏ qua và không thay đổi dữ liệu, kể cả bạn đã sửa chúng sau seed. Nếu database đã có dữ liệu và thiếu ít nhất một ID seed, lệnh từ chối; nếu đủ các ID seed thì bỏ qua kể cả có thêm task khác. Không tự xóa database để vượt qua cảnh báo này.

## 6 Xem dữ liệu và kiểm tra tính toàn vẹn

```bash
npm run db:inspect
```

Lệnh mở database chỉ đọc và hiển thị đường dẫn file, lịch sử migration, danh sách task, số receipt và integrity_check. Với bộ seed mới, mong đợi có 3 task, 1 tombstone, 0 receipt và `Integrity: ok`.

Nếu file chưa tồn tại, inspect báo lỗi thay vì tạo database mới rỗng. Không cần cài phần mềm GUI để hoàn thành bước này.

## 7 Khởi động server có database

```bash
npm start
```

Server yêu cầu database và schema đã tồn tại, kiểm tra hai bảng trước khi mở port. Không tự migrate, seed hoặc reset. Nếu báo thiếu schema, kiểm tra đường dẫn rồi chạy lại bước 4, không tạo bảng thủ công.

Giữ terminal chạy; ở terminal thứ hai:

```bash
curl -i http://localhost:3000/api/v1/health
```

Mong đợi HTTP 200, body rỗng. Health truy vấn hai bảng trên connection đang dùng; nếu lỗi thì trả 503. Đổi URL nếu PORT khác 3000. Health không thay thế kiểm thử nghiệp vụ.

Nhấn Ctrl+C ở terminal server để dừng. Chạy lại server và `npm run db:inspect` để thấy dữ liệu vẫn còn. Không cần seed lại khi restart.

## 8 Chạy kiểm thử tự động

```bash
npm test
```

Hiện có 12 test database:

1. Migration chạy lại và seed không tạo trùng/ghi đè sửa mới.
2. Migration lỗi rollback cả DDL và lịch sử.
3. Từ chối sửa migration đã chạy.
4. Task/receipt/tombstone còn khi tiến trình Node khác mở lại file.
5. Chặn trường không hợp lệ, gồm ngày không tồn tại và version sai.
6. Không trùng task ID hoặc mutation ID.
7. Ghi receipt lỗi thì thay đổi task cùng transaction rollback.
8. Task và receipt cùng commit thành công.
9. Transaction IMMEDIATE giữ khóa ghi giữa hai connection; conditional update từ version cũ không đổi dữ liệu.
10. Reset có bảo vệ, xóa task/receipt nhưng giữ schema.
11. Seed từ chối dữ liệu khác hoặc thiếu một phần.
12. Đường dẫn tính từ server; inspect không tạo file chưa có.

Test dùng file SQLite tạm và dọn sau chạy, không sửa file demo. Thành công ở đây chỉ chứng minh nền tảng database; chưa chứng minh API đã có idempotency hoặc Android đã đồng bộ đúng.

## 9 Reset khi cần bắt đầu bộ demo mới

Đây là thao tác xóa dữ liệu, không phải bước bắt buộc mỗi lần chạy. Dừng server, thống nhất cùng A và làm sạch dữ liệu cũ trên cả hai thiết bị theo quy trình của nhóm trước khi tiếp tục đồng bộ.

Kiểm tra DATABASE_PATH qua `npm run db:inspect` để biết đúng file sẽ reset. Khi đã quyết định reset:

```bash
npm run db:reset -- --confirm-reset
npm run db:seed
npm run db:inspect
```

Reset chỉ cho phép ở development/test với cờ xác nhận. Trong một transaction, xóa receipt trước rồi xóa task; giữ schema và lịch sử migration. Không reset riêng receipt vì retry cũ có thể bị coi là mutation mới. Không xóa riêng tombstone vì thiết bị khác có thể chưa nhận thao tác xóa.

## 10 Kiểm tra và commit Bước 4

Trở về gốc repo:

```bash
cd ..
git diff --check
git status --short
git check-ignore server/storage/studyflow.db server/.env server/node_modules/
```

Lệnh check-ignore cần liệt kê ba đường dẫn trên. Không thêm database, .env hoặc node_modules vào Git. Stage đúng phần Bước 4:

```bash
git add server/package.json server/package-lock.json
git add server/src/database/ server/tests/database.test.js
git add server/src/app.js server/src/server.js server/README.md
git add docs/database-design.md docs/backend-step-4.md
git diff --cached --check
git diff --cached --stat
git diff --cached
```

Sau khi xem đúng nội dung và kiểm tra đã đạt:

```bash
git commit -m "feat(server): bổ sung SQLite migration và seed" -m "Tạo schema task và receipt, công cụ database, kiểm tra kết nối server và test nền tảng transaction/persistence."
git status -sb
```

Nếu muốn đưa lên branch remote hiện tại, chạy `git push` sau khi kiểm tra branch. Commit được người dùng thực hiện; việc triển khai Bước 4 không tự commit/push.

## Điều kiện hoàn thành

- Migration tạo database được từ đầu và chạy lại an toàn.
- Seed có UUID cố định, không trùng hoặc ghi đè sửa mới.
- Inspect cho thấy dữ liệu và integrity ok.
- Server mở database trước khi phục vụ health.
- Test rollback, khóa ghi và lưu bền vững đạt.
- Có README và mô tả schema đúng hiện trạng.

Sau mốc này chuyển Bước 5 để validation HTTP và xử lý lỗi; không coi các CHECK trong SQLite là đủ để nhận request từ Android.
