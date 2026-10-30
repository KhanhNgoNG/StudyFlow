# StudyFlow Server

Backend của StudyFlow sử dụng Node.js, JavaScript ES modules, Express 5 và SQLite qua `better-sqlite3`. Health, API đọc và POST/PUT/DELETE task đã hoạt động trong app chính. API ghi dùng lại executor mutation/receipt của Bước 7, không có luồng idempotency thứ hai. Bước 9 audit và bổ sung integration test, không thay đổi API hoặc mã production. Hợp đồng chung với Android nằm trong [giao thức đồng bộ](../contracts/sync-protocol.md) và [OpenAPI](../contracts/openapi.yaml). Các hướng dẫn [Bước 4](../docs/backend-step-4.md), [Bước 5](../docs/backend-step-5.md), [Bước 6](../docs/backend-step-6.md) ghi lại từng mốc trước; xem [thiết kế database](../docs/database-design.md), [ca kiểm thử](../docs/testing/test-cases.md) và [kết quả thực tế](../docs/testing/test-results.md) về hiện trạng.

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

## API ghi công việc — Bước 8

| Method | Endpoint | Mutation mới thành công |
|---|---|---|
| POST | `/api/v1/tasks` | Giữ UUID client, tạo version 1, timestamp UTC của server, trả 201 với task đầy đủ. ID đã tồn tại, kể cả tombstone, trả 409 VERSION_CONFLICT. |
| PUT | `/api/v1/tasks/{id}` | Kiểm tra tồn tại/version, thay toàn bộ nghiệp vụ, tăng version một, trả 200. Không upsert hoặc tự bỏ qua conflict khi cùng nội dung. |
| DELETE | `/api/v1/tasks/{id}?baseVersion=2` | Task sống: giữ nội dung, đặt isDeleted=true, tăng version một, trả 200. Không xóa vật lý hoặc trả 204. |

POST/PUT bắt buộc Content-Type application/json. Mọi thao tác ghi cần Idempotency-Key UUID; PUT phải có ID body khớp URL. description="", completed=false và dueDate=null được lưu đúng, không fallback về nội dung cũ. version/serverUpdatedAt client gửi không được dùng làm dữ liệu có thẩm quyền. Task chưa tồn tại ở PUT/DELETE trả 404; version khác trả 409 với currentTask đầy đủ, không ghi receipt thành công.

Quyết định chính thức bổ sung ở Bước 8: DELETE bằng key mới trên tombstone, baseVersion khớp, là **thành công không đổi task**. Trả tombstone hiện tại, giữ nguyên version, serverUpdatedAt/nội dung, nhưng ghi receipt mới. Version cũ vẫn 409. PUT isDeleted=false với version khớp có thể phục hồi tombstone; retry DELETE cũ sau phục hồi chỉ trả snapshot tombstone từ receipt, không xóa lại task hiện tại. Quyết định đã được ghi vào Sync Protocol/OpenAPI và mô tả JSON mẫu.

Service chạy kiểm tra tồn tại/version và SQL trong callback của cùng transaction IMMEDIATE do executor quản lý. Repository dùng UPDATE có điều kiện id/version, kiểm tra số hàng đổi; không đọc version ngoài transaction. Trước khi tăng, kiểm tra miền số nguyên an toàn: MAX_SAFE_INTEGER-1 có thể tăng đến MAX_SAFE_INTEGER; nếu cần tăng tiếp thì trả lỗi server 500 hiện có, rollback và không ghi receipt. Version cũ vẫn conflict trước bước này. DELETE no-op trên tombstone ở MAX không cần tăng nên vẫn thành công.

Controller chỉ kiểm tra transport, chuyển nguyên path/query/JSON vào service và gửi status/body sau commit. JSON parser ghi lại số byte để body 0 byte có header JSON không bị nhầm với payload `{}` thật. DELETE không body được chấp nhận; JSON `{}`/null thực sự được fingerprint rồi bị validation trong nhánh mới từ chối. Payload non-JSON có nội dung trả 415, không bị bỏ qua rồi coi như request không body. UUID/task/body/version validation đầy đủ vẫn sau lookup receipt.

## Luồng mutation dùng chung — Bước 7

Mutation là một thao tác gửi POST/PUT/DELETE có UUID Idempotency-Key riêng. Receipt là biên nhận thành công lưu trong SQLite; fingerprint SHA-256 giúp kiểm tra cùng key có thật sự là cùng request hay bị dùng lại cho nội dung khác.

`createMutationExecutor(database).execute(input, apply)` được service ghi dùng lại. input gồm method, path đầy đủ `/api/v1/...` (không kèm query), query chuỗi HTTP, payload JSON chưa trim ở body và idempotencyKey. Callback đồng bộ nhận `{ database, request }`, dùng dữ liệu đã validation của Bước 5 để áp dụng nghiệp vụ rồi trả `{ status, body }`. Executor trả `{ status, body, replayed }` sau COMMIT; controller chỉ gửi `res.status(status).json(body)`.

```text
Nhận diện tối thiểu → fingerprint raw JSON → BEGIN IMMEDIATE → tìm receipt
  Có receipt, cùng request → status/body snapshot cũ → không chạy callback
  Có receipt, khác request → 409 MUTATION_ID_REUSED
  Chưa có → validation Bước 5 → callback ghi task/kiểm tra version
    → kiểm tra response khớp hàng đã lưu → ghi receipt → COMMIT → trả response
```

Ví dụ Android gửi tạo task, server commit task version 1 + receipt nhưng kết nối đứt trước khi Android nhận response. Android gửi lại cùng key/request. Executor đọc receipt trước callback nên trả lại chính response 201/version 1 cũ, kể cả task hiện đã bị sửa/xóa tiếp. Nếu kiểm tra version trước receipt, retry này có thể bị báo xung đột sai; nếu task và receipt không cùng transaction, retry có thể áp dụng lần hai.

Fingerprint sort key object đệ quy, giữ thứ tự array, mọi query/payload kể cả trường chưa được validator sử dụng. Không trim title, ép kiểu baseVersion, normalize Unicode hoặc thêm thời gian/mutation ID vào hash. Body thiếu khác null; query string `01` khác `1`; giữ cả dấu -0 của số JSON đã parse. Unsupported/repeated query được hash rồi validation trong nhánh mutation mới; request cùng key khác nội dung vẫn là 409 trước lỗi nghiệp vụ. Chi tiết canonicalization và giới hạn callback ở [thiết kế database](../docs/database-design.md#dấu-nhận-diện-request-ở-bước-7).

Callback phải đồng bộ, dùng đúng connection được cung cấp, không tự COMMIT/ROLLBACK hoặc lên lịch ghi sau khi trả về. Executor chặn transaction ngoài đang mở và AsyncFunction/bound async trước khi gọi, rollback khi kết quả callback/receipt/commit sai. Receipt hỏng báo lỗi server; không chạy lại mutation để sửa receipt. SQLITE_BUSY/LOCKED đi qua lỗi 5xx hiện có, không tự tạo key khác hoặc retry vô hạn.

Bước 8 đã nối route/controller POST/PUT/DELETE và callback CRUD/version trong service. Không gắn `validateCreateTask`/`validateUpdateTask`/`validateDeleteTask` đầy đủ trước executor: thứ tự đó có thể che MUTATION_ID_REUSED hoặc làm mất payload gốc. Parser JSON/Content-Type và kiểm tra nhận diện tối thiểu vẫn chạy trước; path lấy từ originalUrl trước dấu `?` để giữ cả prefix `/api/v1`. Các cơ chế cố ý làm mất response hoặc lỗi SQL chỉ có trong test, không có endpoint gây lỗi/reset của app chính.

## Cấu trúc mã hiện tại

- `src/app.js`: export `createApp()`, ghép service/repository/controller/router đọc và ghi, JSON parser có số byte payload và middleware lỗi; không tự mở port.
- `src/routes/read-routes.js`: khai báo GET health/danh sách/chi tiết và gắn validator Bước 5.
- `src/routes/write-routes.js`: khai báo POST/PUT/DELETE, chỉ gắn kiểm tra Content-Type tối thiểu trước controller.
- `src/controllers/task-controller.js`, `health-controller.js`: điều phối truy vấn và trả HTTP response; health xử lý 200/503 rỗng.
- `src/services/task-mutation-service.js`: callback POST/PUT/DELETE cho executor; kiểm tra tồn tại/version, giữ UUID, server timestamp, tombstone/no-op và giới hạn version.
- `src/repositories/task-repository.js`: SELECT, INSERT và UPDATE có điều kiện id/version bằng binding; giữ nguyên nội dung khi xóa logic. `health-repository.js` chỉ kiểm tra schema/connection. Các repository không tự mở connection khác.
- `src/server.js`: tạo app, mở port và xử lý lỗi khởi động.
- `src/config/env.js`: đọc cấu hình và kiểm tra `PORT`.
- `src/database/connection.js`: mở SQLite, bật foreign key, WAL, timeout khóa và synchronous FULL.
- `src/database/migrate.js`, `migrations/001_initial.sql`: tạo schema và theo dõi migration.
- `src/database/seed.js`, `seeds/demo-tasks.json`: nạp dữ liệu mẫu cố định và reset có xác nhận.
- `src/database/cli.js`: các lệnh migrate, seed, inspect và reset.
- `src/validation/task-fields.js`: kiểm tra kiểu và giá trị trường nghiệp vụ, UUID, ngày thực, Unicode và số phiên bản an toàn.
- `src/validation/task-requests.js`: kiểm tra POST/PUT/DELETE, ID, header và query; trả bản dữ liệu mới, không sửa request gốc.
- `src/http/task-responses.js`: mapper SQLite → camelCase/boolean, envelope task/danh sách và assertTaskResponse dùng lại cùng quy tắc để kiểm tra callback/receipt snapshot; từ chối dữ liệu sai.
- `src/mutations/request-fingerprint.js`: canonical JSON đệ quy và SHA-256 nhận diện toàn bộ request gốc.
- `src/mutations/mutation-executor.js`: receipt trước validation/callback, transaction IMMEDIATE, kiểm tra kết quả và replay snapshot.
- `src/repositories/mutation-receipt-repository.js`: SELECT/INSERT receipt bằng binding trên cùng connection nghiệp vụ.
- `src/http/api-errors.js`: cấu trúc lỗi validation và factory chuẩn bị TASK_NOT_FOUND, VERSION_CONFLICT, MUTATION_ID_REUSED.
- `src/middleware/validate-task-request.js`: adapter GET và kiểm tra transport JSON dùng chung; full validator ghi được executor gọi sau receipt, không gắn trước route ghi.
- `src/middleware/error-handler.js`: JSON lỗi chung, phân biệt route không tồn tại và lỗi task; log lỗi bất ngờ tại server.
- `tests/database.test.js`: kiểm thử bằng database tạm riêng, không dùng dữ liệu demo.
- `tests/validation.test.js`, `tests/responses.test.js`, `tests/http.test.js`: kiểm thử validator, mapper, factory lỗi và HTTP trên app test riêng.
- `tests/task-repository.test.js`, `tests/read-api.test.js`: repository và integration HTTP đọc dùng database riêng, không dùng dữ liệu demo.
- `tests/request-fingerprint.test.js`, `mutation-executor.test.js`, `mutation-http.test.js`: canonicalization, atomicity/replay/lỗi và HTTP mất phản hồi sau commit.
- `test-support/mutation-worker.js`: helper hai worker/connection thật để kiểm tra tranh khóa cùng mutation ID; không được app chính nạp.
- `tests/task-write-api.test.js`: 18 test HTTP trên app chính cho CRUD/version, replay, rollback, JSON body và mất phản hồi. `tests/task-write-concurrency.test.js`, `test-support/task-write-worker.js`: ba ca cạnh tranh production service bằng hai worker, trigger/gate chỉ thuộc test.
- `tests/write-http-contract.test.js`, `test-support/http-fixture.js`: HTTP production cho biên validation/Unicode, key order/kiểu trong fingerprint và receipt hỏng; mỗi test có schema SQLite mới, port động, timeout và cleanup.
- `tests/restart-replay.test.js`, `test-support/http-process.js`: replay sau restart hai tiến trình Node độc lập, dùng cùng database file tạm. IPC chỉ báo port động và dừng đúng child do test tạo.
- `tests/openapi.test.js`, `test-support/openapi-contract.js`: YAML, local refs, parameter, JSON mẫu và schema của HTTP thực tế. Parser `yaml` chỉ là dev dependency; evaluator giới hạn không thay validator OpenAPI chuyên dụng.
- `scripts/smoke-read-api.js`: chạy tiến trình server thật với database tạm/cổng trống, gửi GET và tự dọn tài nguyên sau kiểm tra.
- `.env.example`: cấu hình mẫu được lưu trong Git.
- `package-lock.json`: khóa phiên bản dependency để cài lại bằng `npm ci`.

## Giới hạn hiện tại

- API đọc/ghi và replay receipt đã hoạt động trong app chính; chưa ghép Retrofit/SyncEngine Android với backend này hoặc kiểm thử đầy đủ trên hai thiết bị Android.
- Không có phân trang, realtime, hợp nhất từng trường, lịch sử khôi phục hoặc endpoint chọn bên thắng.
- Concurrency đã thử với hai worker/kết nối và service thật, chưa phải stress test HTTP tải lớn. Bước 9 chỉ hoàn thiện kiểm thử backend độc lập; chưa triển khai công cụ gây lỗi Bước 10 hoặc benchmark.

Các endpoint task/health trong OpenAPI hiện đã có implementation; các giới hạn kiểm chứng được ghi ở trên.

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

Ngày 30/10/2026 đã chạy **147 test: 147 pass, 0 fail, 0 skip, 0 cancel**. Trước Bước 9 có 136 test; giữ lại toàn bộ và thêm 11 ca có mục đích: 4 HTTP validation/fingerprint/receipt hỏng, 1 restart/replay, 1 race cùng key khác request và 5 kiểm tra hợp đồng. Không đổi test framework hoặc dependency runtime; chỉ thêm `yaml@2.9.1` trong devDependencies/lockfile để kiểm tra YAML ngay trong `npm test`, không phụ thuộc Python của máy.

Lệnh chính là `npm test` sau `cd /Users/tuankhong/Documents/StudyFlow/server`. Máy mới dùng Node `>=22.14.0 <23` theo package và chạy `npm ci` một lần để cài cả devDependencies. Test không cần `.env`, không phụ thuộc database demo hay thứ tự chạy. Chạy riêng các nhóm khi cần chẩn đoán:

```bash
node --test tests/request-fingerprint.test.js tests/mutation-executor.test.js tests/mutation-http.test.js
node --test tests/task-write-api.test.js tests/task-write-concurrency.test.js
node --test tests/write-http-contract.test.js tests/restart-replay.test.js tests/openapi.test.js
```

Unit test gọi riêng validator/mapper/fingerprint để xác định quy tắc sai. Integration test nối nhiều thành phần thật: HTTP → parser/router/controller → service/executor → SQLite đã migrate → JSON response. Database test kiểm tra transaction và persistence trực tiếp; test process còn loại bỏ khả năng replay chỉ nhờ cache RAM. Các lớp này bảo vệ những lỗi khác nhau, không thay thế nhau.

Các test Bước 7–8 giữ nguyên kiểm tra fingerprint, version/conflict/404, no-upsert, tombstone/phục hồi, replay snapshot, rollback callback/INSERT receipt/COMMIT, overflow và mất response sau commit. Bước 9 thêm kiểm tra production HTTP ở biên Unicode/validation, đảo key JSON vẫn replay nhưng đổi kiểu/nội dung vẫn 409, receipt hỏng trả 500 kín và không áp dụng lại. Test restart ghi hai task/bảy receipt ở process A, dừng A rồi mở B trên cùng file và replay cả bảy snapshot, giữ task sống version 4 và tombstone version 2.

Ba ca concurrency dùng hai Worker/hai connection thực sự tranh write lock: contender nhận SQLITE_BUSY khi writer chưa commit rồi được yêu cầu retry cùng request. Khác key/cùng baseVersion nhận VERSION_CONFLICT; cùng key/cùng request nhận replay; cùng key/khác request nhận MUTATION_ID_REUSED trước version conflict. Gate/trigger và timeout chỉ nằm ở test. Đã chạy riêng file này thêm năm lần để kiểm tra độ ổn định của phối hợp, mỗi lần 3/3 đạt; đây không phải benchmark, stress test hoặc chứng minh mọi lịch interleaving.

Trong kết quả TAP, `ok` là assertion đạt, `not ok` đi kèm tên ca/file/stack để chẩn đoán. Cuối lệnh có tổng `tests`, `pass`, `fail`, `skipped`, `cancelled`; lệnh thành công trả exit code 0. Với 147 test hiện tại, kết quả mong đợi là pass 147 và các số còn lại bằng 0. Nếu lỗi listen EPERM trong sandbox, cấp quyền mở localhost rồi chạy lại; không skip hoặc đổi assertion. Xem [bảng đối chiếu và ca quan trọng](../docs/testing/test-cases.md), [kết quả/giới hạn](../docs/testing/test-results.md).

Ở Bước 8 đã chạy cURL thật trên tiến trình `src/server.js` riêng với database tạm, cổng 3001: 15 request đạt, gồm tạo 201/version 1 → PUT 200/version 2 → DELETE 200/version 3, stale version 409, GET tombstone, retry snapshot cũ, DELETE no-op có receipt mới và phục hồi version 4. Cuối chuỗi có một task sống version 4, năm receipt thành công, integrity_check=ok. Đã dừng đúng server đã tạo và dọn thư mục tạm; không dùng database demo. Đây là bằng chứng mốc Bước 8, không tính là chạy cURL mới trong Bước 9.

OpenAPI hiện được kiểm tra tự động trong `npm test`: YAML parse/không trùng key, 51 local $ref resolve, parameter bắt buộc/default includeDeleted, 12 external example occurrence và toàn bộ 13 JSON fixture, cùng request/response HTTP thật cho CRUD/404/409/422/health. Evaluator hỗ trợ các keyword hiện có và từ chối keyword chưa hỗ trợ; kiểm tra độ dài theo Unicode code point, UUID/ngày lịch/UTC. Đây là subset schema, không phải validator OpenAPI/JSON Schema đầy đủ hay toàn bộ RFC 3339. OpenAPI không có content schema cho health/5XX nên test health còn assert body rỗng theo thiết kế; các lỗi hạ tầng kín được test riêng.

`npm run test:smoke` tạo database tạm với fixture sống/tombstone, chạy tiến trình `src/server.js` riêng trên cổng loopback trống và gửi 11 GET bằng HTTP thật. Script tự dừng đúng tiến trình của nó, so dữ liệu trước/sau và dọn thư mục tạm; không đọc `.env`, không dùng/reset/seed lại database demo. Xem [kết quả Bước 6](../docs/backend-step-6.md#kiểm-thử-đã-chạy) về giới hạn kiểm chứng và kiểm tra OpenAPI bằng script giới hạn, không phải validator chuyên dụng.

## Tái hiện chuỗi ghi bằng cURL với database tạm

Terminal thứ nhất: tạo database riêng ngoài repo, chỉ migrate schema, không nạp/reset demo. Cổng 3001 phải đang trống; nếu server báo EADDRINUSE thì không dừng tiến trình khác.

```bash
cd /Users/tuankhong/Documents/StudyFlow/server
STUDYFLOW_STEP8_TMP="$(mktemp -d)"
DATABASE_PATH="$STUDYFLOW_STEP8_TMP/studyflow.db" node --input-type=module -e 'import {openDatabase} from "./src/database/connection.js"; import {migrateDatabase} from "./src/database/migrate.js"; const db=openDatabase(process.env.DATABASE_PATH); migrateDatabase(db); db.close();'
NODE_ENV=test HOST=127.0.0.1 PORT=3001 DATABASE_PATH="$STUDYFLOW_STEP8_TMP/studyflow.db" node src/server.js
```

Terminal thứ hai chạy đúng các UUID và JSON mẫu sau. Chỉ mutation mới đổi key; retry giữ nguyên key/payload/query. Cả hai terminal không cần đọc `.env` cho ví dụ này.

```bash
cd /Users/tuankhong/Documents/StudyFlow/server
curl -i http://127.0.0.1:3001/api/v1/health
curl -i -X POST http://127.0.0.1:3001/api/v1/tasks -H 'Content-Type: application/json' -H 'Idempotency-Key: 7fe7b179-6677-43ec-99e2-c0c035a184a8' --data-binary @../contracts/examples/create-task-request.json
curl -i http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814
curl -i -X PUT http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814 -H 'Content-Type: application/json' -H 'Idempotency-Key: 345ccf57-5bf5-43d7-9175-45375211bbaa' --data-binary @../contracts/examples/update-task-request.json
curl -i -X PUT http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814 -H 'Content-Type: application/json' -H 'Idempotency-Key: 00000000-0000-4000-8000-000000000003' --data-binary @../contracts/examples/update-task-request.json
curl -i -X DELETE 'http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814?baseVersion=2' -H 'Idempotency-Key: 4099f1b3-084d-41ad-96f8-736621763d0d'
curl -i http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814
curl -i -X POST http://127.0.0.1:3001/api/v1/tasks -H 'Content-Type: application/json' -H 'Idempotency-Key: 7fe7b179-6677-43ec-99e2-c0c035a184a8' --data-binary @../contracts/examples/create-task-request.json
curl -i -X DELETE 'http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814?baseVersion=2' -H 'Idempotency-Key: 4099f1b3-084d-41ad-96f8-736621763d0d'
curl -i -X DELETE 'http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814?baseVersion=3' -H 'Idempotency-Key: 00000000-0000-4000-8000-000000000004'
curl -i -X PUT http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814 -H 'Content-Type: application/json' -H 'Idempotency-Key: 00000000-0000-4000-8000-000000000005' --data-binary '{"id":"612954cb-842b-4d60-a0c8-48a7cc401814","baseVersion":3,"title":"Giữ công việc sau tombstone","description":"Triển khai CRUD và kiểm tra gửi lại theo hợp đồng","subjectCode":"MOBILE","dueDate":null,"priority":"HIGH","completed":false,"isDeleted":false}'
curl -i -X DELETE 'http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814?baseVersion=3' -H 'Idempotency-Key: 00000000-0000-4000-8000-000000000004'
curl -i http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814
curl -i 'http://127.0.0.1:3001/api/v1/tasks?includeDeleted=true'
curl -i -X DELETE 'http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814?baseVersion=3' -H 'Idempotency-Key: 00000000-0000-4000-8000-000000000006'
```

Kết quả lần lượt: 200, 201, 200, 200, 409, 200, 200, 201, 200, 200, 200, 200, 200, 200, 409. DELETE cuối dùng key mới và version cũ nên conflict; DELETE ngay sau phục hồi dùng key no-op cũ nên replay tombstone version 3, trong khi GET tiếp theo vẫn là task sống version 4. Dừng server tự chạy bằng Ctrl+C ở terminal thứ nhất. Dữ liệu ví dụ nằm trong thư mục tạm, không ở database demo của repo.

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
