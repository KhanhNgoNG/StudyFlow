# StudyFlow Server

Backend của StudyFlow sử dụng Node.js, JavaScript ES modules, Express 5 và SQLite qua `better-sqlite3`. Hiện có nền tảng database, migration, seed, endpoint health và các thành phần validation/response/error middleware cho API task sau này. Hợp đồng API dùng chung với Android nằm trong [giao thức đồng bộ](../contracts/sync-protocol.md) và [OpenAPI](../contracts/openapi.yaml). Xem [hướng dẫn Bước 4](../docs/backend-step-4.md) về database và [hướng dẫn Bước 5](../docs/backend-step-5.md) về validation, luồng request/lỗi và kiểm thử.

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

- `src/app.js`: export `createApp()`, đọc JSON, khai báo health, gắn middleware route/lỗi ở cuối; không tự mở port.
- `src/server.js`: tạo app, mở port và xử lý lỗi khởi động.
- `src/config/env.js`: đọc cấu hình và kiểm tra `PORT`.
- `src/database/connection.js`: mở SQLite, bật foreign key, WAL, timeout khóa và synchronous FULL.
- `src/database/migrate.js`, `migrations/001_initial.sql`: tạo schema và theo dõi migration.
- `src/database/seed.js`, `seeds/demo-tasks.json`: nạp dữ liệu mẫu cố định và reset có xác nhận.
- `src/database/cli.js`: các lệnh migrate, seed, inspect và reset.
- `src/validation/task-fields.js`: kiểm tra kiểu và giá trị trường nghiệp vụ, UUID, ngày thực, Unicode và số phiên bản an toàn.
- `src/validation/task-requests.js`: kiểm tra POST/PUT/DELETE, ID, header và query; trả bản dữ liệu mới, không sửa request gốc.
- `src/http/task-responses.js`: mapper SQLite → camelCase/boolean và envelope task/danh sách; từ chối bản ghi sai.
- `src/http/api-errors.js`: cấu trúc lỗi validation và factory chuẩn bị TASK_NOT_FOUND, VERSION_CONFLICT, MUTATION_ID_REUSED.
- `src/middleware/validate-task-request.js`: adapter Express cho validator; dữ liệu hợp lệ nằm ở `res.locals.validatedRequest`. Chưa gắn vào app chính vì chưa có API task.
- `src/middleware/error-handler.js`: JSON lỗi chung, phân biệt route không tồn tại và lỗi task; log lỗi bất ngờ tại server.
- `tests/database.test.js`: kiểm thử bằng database tạm riêng, không dùng dữ liệu demo.
- `tests/validation.test.js`, `tests/responses.test.js`, `tests/http.test.js`: kiểm thử validator, mapper, factory lỗi và HTTP trên app test riêng.
- `.env.example`: cấu hình mẫu được lưu trong Git.
- `package-lock.json`: khóa phiên bản dependency để cài lại bằng `npm ci`.

## Giới hạn hiện tại

- Chưa triển khai API công việc: danh sách, chi tiết, tạo, cập nhật và xóa logic.
- Đã có validator HTTP dùng lại; PUT/DELETE chỉ kiểm tra baseVersion là số nguyên an toàn >= 1, chưa kiểm tra task tồn tại hoặc version khớp database.
- Đã có bảng receipt; chưa có logic tạo hash request, tìm receipt trước version hoặc replay response qua API.
- Các factory lỗi 404/409 mới chuẩn bị cấu trúc response, chưa có nghiệp vụ phát sinh lỗi qua API thật.
- Chưa kiểm thử CRUD/sync Android với backend thật. Các route task trong test chỉ kiểm tra middleware và không được nạp bởi `src/app.js`.

Các endpoint và schema trong OpenAPI mô tả hợp đồng cần triển khai; không có nghĩa mọi endpoint đã hoạt động.

## Quy tắc validation và lỗi của Bước 5

POST/PUT phải đủ 8 trường nghiệp vụ và baseVersion; không điền default. Title dùng `String.trim()` rồi đếm Unicode code point; emoji đơn như 😀 tính 1, dấu tổ hợp tính riêng, không normalize NFC/NFD. NUL và surrogate đơn bị từ chối để tránh khác biệt khi lưu/đọc SQLite. Đây là cách làm rõ phần “ký tự” chưa được hợp đồng mô tả chi tiết, cần đối chiếu phía Android; xem [quyết định và điểm chưa chốt](../docs/backend-step-5.md#quyết-định-triển-khai-và-điểm-hợp-đồng-chưa-chốt).

Query baseVersion chỉ nhận chuỗi chữ số thập phân trong khoảng 1–9007199254740991. includeDeleted chỉ nhận `true`/`false`; bỏ qua tham số này trả object rỗng từ validator, **không tự chọn default**. Trường ngoài whitelist, kể cả version/serverUpdatedAt, không được chuyển tới dữ liệu ghi; request gốc vẫn nguyên vẹn để bước receipt sử dụng sau này.

Lỗi nghiệp vụ hợp đồng dùng 422 VALIDATION_ERROR, 404 TASK_NOT_FOUND hoặc 409 VERSION_CONFLICT/MUTATION_ID_REUSED. Middleware còn áp dụng các chính sách hạ tầng dưới đây, **chưa được đặc tả chi tiết trong hợp đồng/OpenAPI hiện tại**:

| Tình huống | HTTP / code triển khai |
|---|---|
| JSON sai cú pháp | 400 / INVALID_JSON |
| Body vượt giới hạn parser mặc định 100kb | 413 / PAYLOAD_TOO_LARGE |
| Sai Content-Type ở validator POST/PUT hoặc encoding không hỗ trợ | 415 / UNSUPPORTED_MEDIA_TYPE |
| Body bị hủy hoặc sai Content-Length do parser báo | 400 / BAD_REQUEST |
| Không có route tương ứng | 404 / ROUTE_NOT_FOUND |
| Lỗi bất ngờ, gồm bản ghi không serialize được | 500 / INTERNAL_ERROR |

Các mã hạ tầng là lựa chọn của Bước 5 để trả JSON rõ ràng, không phải thỏa thuận đồng bộ mới đã được hai phía xác nhận. Client không nhận stack trace, SQL, đường dẫn hay payload của lỗi. Health giữ body rỗng 200/503 như trước.

## Kiểm thử backend

```bash
npm test
```

Không cần `.env`, migrate hoặc seed database demo để chạy test. Test dùng file SQLite tạm hoặc `:memory:`, đóng/dọn sau khi chạy. HTTP test mở port ngẫu nhiên trên `127.0.0.1` rồi đóng server; môi trường sandbox cần cho phép listen localhost.

Hiện có 56 test: 12 database, 30 validation, 4 response/factory và 10 HTTP/error middleware. Bao gồm các test database cũ cùng biên Unicode/ngày/boolean/số nguyên, fixture hợp đồng, lỗi không lộ dữ liệu và health 200/503. Xem kết quả chạy thực tế, lệnh chạy từng nhóm và giới hạn kiểm chứng trong [Bước 5](../docs/backend-step-5.md#cách-chạy-test-và-kết-quả-đã-chạy).

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
