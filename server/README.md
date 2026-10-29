# StudyFlow Server

Backend của StudyFlow sử dụng Node.js, JavaScript ES modules, Express 5 và SQLite qua `better-sqlite3`. Hiện có nền tảng database, migration/seed, API đọc danh sách/chi tiết task và health. Phần validation, serializer và middleware lỗi của Bước 5 được dùng lại trong API đọc. Hợp đồng chung với Android nằm trong [giao thức đồng bộ](../contracts/sync-protocol.md) và [OpenAPI](../contracts/openapi.yaml). Xem [Bước 4](../docs/backend-step-4.md) về database, [Bước 5](../docs/backend-step-5.md) về validation và [Bước 6](../docs/backend-step-6.md) về API đọc cùng bằng chứng kiểm thử/chạy thử.

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

Kết quả mong đợi là HTTP `200 OK` với body rỗng. Server kiểm tra hai bảng nghiệp vụ trước khi mở port; health kiểm tra connection và khả năng truy vấn hai bảng, trả `503` rỗng nếu thiếu database, connection đóng hoặc schema không truy vấn được. Health không migrate/seed/reset và không chứng minh API ghi hoặc đồng bộ đã hoạt động.

Nếu đổi `PORT`, thay cổng tương ứng trong URL.

## API đọc công việc

```bash
curl -i 'http://localhost:3000/api/v1/tasks?includeDeleted=true'
curl -i http://localhost:3000/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814
```

Lấy ID thực tế từ danh sách để gọi chi tiết; ID ví dụ không được bảo đảm tồn tại trong mọi database. Danh sách trả `200 {"tasks": [...]}`, rỗng trả `200 {"tasks": []}`. Chi tiết trả `200 {"task": ...}`, kể cả tombstone. UUID hợp lệ chưa tồn tại trả `404 TASK_NOT_FOUND` kèm taskId; UUID sai trả `422 VALIDATION_ERROR`.

GET dùng parameter binding cho ID; không cập nhật task, timestamp/version hay receipt. Danh sách dùng một SELECT `.all()` với `ORDER BY id` để có snapshot nhất quán và thứ tự ổn định. Thứ tự ID chỉ là lựa chọn triển khai, không phải thứ tự nghiệp vụ. Không mở transaction IMMEDIATE hoặc giữ transaction khi gửi HTTP.

Quyết định người dùng chốt ở Bước 6: thiếu includeDeleted hoặc true trả cả task sống và tombstone; false chỉ trả task sống. Giá trị sai hoặc query lặp trả 422 VALIDATION_ERROR với fieldErrors.includeDeleted. Đã cập nhật Sync Protocol/OpenAPI; Android vẫn gửi rõ true khi đồng bộ.

## Cấu trúc mã hiện tại

- `src/app.js`: export `createApp()`, ghép repository/controller/router đọc, JSON parser và middleware lỗi; không tự mở port.
- `src/routes/read-routes.js`: khai báo GET health/danh sách/chi tiết và gắn validator Bước 5.
- `src/controllers/task-controller.js`, `health-controller.js`: điều phối truy vấn và trả HTTP response; health xử lý 200/503 rỗng.
- `src/repositories/task-repository.js`, `health-repository.js`: chỉ truy vấn SQLite; không ghi dữ liệu hoặc tự mở connection khác.
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
- `src/middleware/validate-task-request.js`: adapter Express cho validator; GET danh sách/chi tiết đã dùng trong app chính, kết quả ở `res.locals.validatedRequest`. Adapter ghi vẫn chưa có endpoint tương ứng.
- `src/middleware/error-handler.js`: JSON lỗi chung, phân biệt route không tồn tại và lỗi task; log lỗi bất ngờ tại server.
- `tests/database.test.js`: kiểm thử bằng database tạm riêng, không dùng dữ liệu demo.
- `tests/validation.test.js`, `tests/responses.test.js`, `tests/http.test.js`: kiểm thử validator, mapper, factory lỗi và HTTP trên app test riêng.
- `tests/task-repository.test.js`, `tests/read-api.test.js`: repository và integration HTTP đọc dùng database riêng, không dùng dữ liệu demo.
- `scripts/smoke-read-api.js`: chạy tiến trình server thật với database tạm/cổng trống, gửi GET và tự dọn tài nguyên sau kiểm tra.
- `.env.example`: cấu hình mẫu được lưu trong Git.
- `package-lock.json`: khóa phiên bản dependency để cài lại bằng `npm ci`.

## Giới hạn hiện tại

- API đọc danh sách/chi tiết đã có; chưa triển khai POST, PUT hoặc DELETE task.
- Đã có validator HTTP dùng lại; PUT/DELETE chỉ kiểm tra baseVersion là số nguyên an toàn >= 1, chưa kiểm tra task tồn tại hoặc version khớp database.
- Đã có bảng receipt; chưa có logic tạo hash request, tìm receipt trước version hoặc replay response qua API.
- TASK_NOT_FOUND đã phát sinh từ GET chi tiết; các factory lỗi 409 vẫn mới chuẩn bị, chưa có service ghi/version/receipt.
- Chưa ghép Android với backend thật, chưa kiểm thử retry ghi hoặc đồng bộ đầy đủ.

Các endpoint và schema trong OpenAPI mô tả hợp đồng cần triển khai; không có nghĩa mọi endpoint đã hoạt động.

## Quy tắc validation và lỗi của Bước 5

POST/PUT phải đủ 8 trường nghiệp vụ và baseVersion; không điền default. Title dùng `String.trim()` rồi đếm Unicode code point; emoji đơn như 😀 tính 1, dấu tổ hợp tính riêng, không normalize NFC/NFD. NUL và surrogate đơn bị từ chối để tránh khác biệt khi lưu/đọc SQLite. Đây là cách làm rõ phần “ký tự” chưa được hợp đồng mô tả chi tiết, cần đối chiếu phía Android; xem [quyết định và điểm chưa chốt](../docs/backend-step-5.md#quyết-định-triển-khai-và-điểm-hợp-đồng-chưa-chốt).

Query baseVersion chỉ nhận chuỗi chữ số thập phân trong khoảng 1–9007199254740991. includeDeleted chỉ nhận `true`/`false`; nếu không truyền, validator trả `{ includeDeleted: true }` theo quyết định Bước 6 đã cập nhật hợp đồng. Trường ngoài whitelist, kể cả version/serverUpdatedAt, không được chuyển tới dữ liệu ghi; request gốc vẫn nguyên vẹn để bước receipt sử dụng sau này.

Lỗi nghiệp vụ hợp đồng dùng 422 VALIDATION_ERROR, 404 TASK_NOT_FOUND hoặc 409 VERSION_CONFLICT/MUTATION_ID_REUSED. Middleware còn áp dụng các chính sách hạ tầng dưới đây, **chưa được đặc tả chi tiết trong hợp đồng/OpenAPI hiện tại**:

| Tình huống | HTTP / code triển khai |
|---|---|
| JSON sai cú pháp | 400 / INVALID_JSON |
| Body vượt giới hạn parser mặc định 100kb | 413 / PAYLOAD_TOO_LARGE |
| Sai Content-Type ở validator POST/PUT hoặc encoding không hỗ trợ | 415 / UNSUPPORTED_MEDIA_TYPE |
| Body bị hủy hoặc sai Content-Length do parser báo | 400 / BAD_REQUEST |
| URL có percent encoding sai, router không giải mã được | 400 / BAD_REQUEST |
| Không có route tương ứng | 404 / ROUTE_NOT_FOUND |
| Lỗi bất ngờ, gồm bản ghi không serialize được | 500 / INTERNAL_ERROR |

Các mã hạ tầng là lựa chọn của Bước 5 để trả JSON rõ ràng, không phải thỏa thuận đồng bộ mới đã được hai phía xác nhận. Client không nhận stack trace, SQL, đường dẫn hay payload của lỗi. Health giữ body rỗng 200/503 như trước.

## Kiểm thử backend

```bash
npm test
npm run test:smoke
```

Không cần `.env`, migrate hoặc seed database demo để chạy test. Test dùng file SQLite tạm hoặc `:memory:`, đóng/dọn sau khi chạy. HTTP test mở port ngẫu nhiên trên `127.0.0.1` rồi đóng server; môi trường sandbox cần cho phép listen localhost.

Hiện có 80 test: 12 database, 30 validation, 4 response/factory, 10 HTTP/error middleware, 8 repository và 16 HTTP API đọc. Các test cũ vẫn chạy; kỳ vọng default includeDeleted và health thiếu database đã cập nhật theo Bước 6. Có test hồi quy cho flag/query lặp sau 1.000 khóa và URL mã hóa sai, cùng kiểm tra hai connection WAL trước/sau commit, GET không ghi dữ liệu và health 200/503.

`npm run test:smoke` tạo database tạm với fixture sống/tombstone, chạy tiến trình `src/server.js` riêng trên cổng loopback trống và gửi 11 GET bằng HTTP thật. Script tự dừng đúng tiến trình của nó, so dữ liệu trước/sau và dọn thư mục tạm; không đọc `.env`, không dùng/reset/seed lại database demo. Xem [kết quả Bước 6](../docs/backend-step-6.md#kiểm-thử-đã-chạy) về giới hạn kiểm chứng và kiểm tra OpenAPI bằng script giới hạn, không phải validator chuyên dụng.

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
