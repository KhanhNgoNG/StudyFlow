# Bước 6 backend: API đọc dữ liệu

## Mục tiêu và phạm vi

Đã triển khai GET `/api/v1/tasks`, GET `/api/v1/tasks/{id}` và hoàn thiện GET `/api/v1/health`. API đọc dùng SQLite thật và tái sử dụng validator, mapper/serializer, ApiError và error middleware Bước 5. Không thêm dependency, migration, service chuyển tiếp hoặc API phụ để ghi/reset dữ liệu.

Chưa triển khai POST/PUT/DELETE task, đối chiếu baseVersion, hash mutation, ghi/replay receipt, retry ghi hoặc thay đổi Android. Không có phân trang, tài khoản, realtime hay đồng bộ tăng dần ở bước này.

Trước khi sửa đã đọc AGENTS, Sync Protocol/OpenAPI/JSON mẫu, README, thiết kế database và mã Bước 5 thực tế. Nhánh hiện tại là `codex/backend-api-contract`. Các file Bước 5 đang chưa commit khi bắt đầu; chúng được giữ lại, chỉ sửa phần cần thiết cho API đọc và quyết định mới của người dùng.

## Quyết định includeDeleted đã được chốt

Trong lúc thực hiện Bước 6, người dùng xác nhận ngày **29/10/2026**:

| Request | Kết quả |
|---|---|
| GET `/api/v1/tasks` | Mặc định includeDeleted=true, trả cả task sống và tombstone. |
| GET `/api/v1/tasks?includeDeleted=true` | Trả cả task sống và tombstone. |
| GET `/api/v1/tasks?includeDeleted=false` | Chỉ trả task có isDeleted=false. |
| Giá trị sai hoặc query includeDeleted bị lặp | 422 VALIDATION_ERROR, fieldErrors.includeDeleted. |

Quyết định này đã được cập nhật vào [Sync Protocol](../contracts/sync-protocol.md), [OpenAPI](../contracts/openapi.yaml) với `default: true`, và [hướng dẫn JSON mẫu](../contracts/examples/README.md). OpenAPI GET danh sách cũng bổ sung response 422. Android vẫn gửi rõ includeDeleted=true khi đồng bộ; không cần sửa Android để dựa vào default.

Bước 5 từng để ngỏ default; phần mô tả mốc cũ trong `backend-step-5.md` là lịch sử trước quyết định này. Không dùng Boolean("false"): validator chỉ nhận đúng một chuỗi `true`/`false`, trả boolean thật và gán default true khi không có tham số. Query lặp (kể cả hai giá trị giống nhau), array/object hoặc dạng ngoặc như `includeDeleted[]=false` đều bị từ chối, không được hiểu nhầm thành thiếu tham số.

## Các file tạo và sửa

| File | Thay đổi và vai trò |
|---|---|
| Tạo `server/src/routes/read-routes.js` | Khai báo ba route GET; gắn validator danh sách/ID trước controller. Không khai báo method ghi. |
| Tạo `server/src/controllers/task-controller.js` | Chọn truy vấn toàn bộ/task sống từ query đã kiểm tra; xử lý task không tồn tại; dùng serializer để trả response. |
| Tạo `server/src/controllers/health-controller.js` | Trả 200 rỗng khi repository kiểm tra sẵn sàng; log chẩn đoán và trả 503 rỗng khi thất bại. |
| Tạo `server/src/repositories/task-repository.js` | Ba truy vấn chỉ đọc: findAll, findActive, findById. Chọn rõ 10 cột; ID dùng parameter binding. |
| Tạo `server/src/repositories/health-repository.js` | Kiểm tra connection mở và hai bảng tasks/mutation_receipts truy vấn được; không migrate/seed/reset. |
| Sửa `server/src/app.js` | Ghép repository/controller/router tại `/api/v1`; giữ JSON parser/error middleware, đọc đầy đủ query thay vì cắt sau 1.000 khóa. Không tự mở port. |
| Sửa `server/src/validation/task-requests.js` | Chốt default true trong validator dùng chung; chặn query ngoặc bị Express simple parser giữ dưới dạng tên khóa. |
| Sửa `server/src/middleware/error-handler.js` | Export errorDiagnostic cho health; URL không giải mã được trả 400 BAD_REQUEST, không giả thành lỗi server. |
| Tạo `server/tests/task-repository.test.js` | 8 test repository, parameter binding, không ghi dữ liệu và isolation bằng hai connection WAL thật. |
| Tạo `server/tests/read-api.test.js` | 16 test integration HTTP trên app chính với database riêng; các mode danh sách, chi tiết, health, query dài và URL mã hóa sai. |
| Sửa `server/tests/http.test.js` | Cập nhật kỳ vọng default, health thiếu database thành 503 và route ghi chưa triển khai thành 404. |
| Sửa `server/tests/validation.test.js` | Cập nhật default true và kiểm tra query ngoặc sai. |
| Tạo `server/scripts/smoke-read-api.js`; sửa script trong `server/package.json` | HTTP smoke test có thể chạy lại bằng npm, dùng database tạm và tiến trình server riêng; không thêm dependency. |
| Sửa `contracts/sync-protocol.md` | Ghi quyết định includeDeleted được người dùng xác nhận. |
| Sửa `contracts/openapi.yaml` | Mô tả true/false/default và response validation 422 của GET danh sách. |
| Sửa `contracts/examples/README.md` | Liên hệ fixture task-list với default/true và mô tả query sai; không đổi schema JSON mẫu. |
| Sửa `server/README.md` | API đọc hiện có, cách chạy, kiến trúc, test và giới hạn mới. |
| Tạo `docs/backend-step-6.md` | Hướng dẫn và kết quả thực tế này. |

Giữ nguyên `server/src/http/task-responses.js`, `api-errors.js`, `middleware/validate-task-request.js`, schema/migration/seed, connection và bootstrap `server.js`. Không tạo bộ mapper, validator hoặc error handler thứ hai. Toàn bộ file Android giữ nguyên.

## Luồng hoạt động cho người mới

**Route** là bảng chỉ đường: method GET và đường dẫn nào sẽ chạy middleware/controller nào. **Controller** nhận kết quả validation, gọi repository, chọn status và đưa kết quả qua serializer. **Repository** chứa SQL, biết cách truy vấn dữ liệu lưu trữ nhưng không biết request/response HTTP. **Mapper** biết cách đổi tên/kiểu dữ liệu SQLite thành task JSON của hợp đồng.

```text
HTTP GET /api/v1/tasks?includeDeleted=true
  → app /api/v1 → route /tasks
  → validateListTasks: res.locals.validatedRequest = { includeDeleted: true }
  → taskController.list
  → taskRepository.findAll → một SELECT → SQLite trả mảng hàng
  → taskListResponse → taskFromRow cho từng hàng
  → 200 application/json; charset=utf-8 với {"tasks": [...]}
```

Nếu false, controller gọi findActive với điều kiện `WHERE is_deleted = 0`. Không truyền query được validator chuẩn hóa thành true trước khi controller chạy. `ORDER BY id` cho kết quả ổn định, không phải quy tắc ưu tiên công việc của sản phẩm.

```text
HTTP GET /api/v1/tasks/{id}
  → validateGetTask: UUID đúng định dạng?
  → taskController.detail → taskRepository.findById(id)
  → SELECT ... WHERE id = ? với binding, không nối ID vào SQL
  → có hàng: taskResponse(row) → 200 {"task": ...}
  → không có hàng: taskNotFoundError(id) → error middleware → 404 TASK_NOT_FOUND
```

ID sai bị 422 trước truy vấn; database lỗi thật được throw lên middleware và trả 500 INTERNAL_ERROR, không biến thành 404 hoặc danh sách rỗng. Route HTTP không tồn tại dùng 404 ROUTE_NOT_FOUND theo chính sách hạ tầng đã giải thích ở Bước 5. Bước 6 không thêm code lỗi hoặc envelope mới.

Không cần tầng service riêng vì API đọc chưa có nghiệp vụ ngoài validation, chọn truy vấn và serialize. Repository dùng connection được app truyền vào, không tự mở database khác. Statement được prepare khi method chạy; lỗi SQL không bị catch rồi trả thành công.

## Dữ liệu, tombstone và danh sách rỗng

Serializer Bước 5 trả đúng id, title, description, subjectCode, dueDate, priority, completed, isDeleted, version, serverUpdatedAt. Các boolean SQLite 0/1 được đổi thành boolean JSON; dueDate không có hạn là null. Không lộ cột snake_case, baseVersion hoặc metadata Android. Bản ghi sai/thiếu trường làm response thất bại bằng 500, không tự trim/fill default để che lỗi; danh sách có hàng sai không trả thành công một phần.

Tombstone vẫn là hàng có ID và nội dung, với isDeleted=true. GET chi tiết vẫn trả 200 để thiết bị khác nhận được bằng chứng xóa và version của nó. Ẩn tombstone khỏi GET chi tiết hoặc coi ID thiếu trong danh sách là đã xóa sẽ phá quy tắc đồng bộ offline.

Danh sách rỗng là kết quả đọc thành công: truy vấn đã chạy và không có hàng phù hợp, nên trả 200 với `{"tasks": []}`. 404 chỉ dành cho việc tìm một task ID hợp lệ nhưng chưa tồn tại; không dùng cho tập kết quả rỗng.

GET không UPDATE/INSERT/DELETE, không tăng version, thay timestamp, phục hồi tombstone hoặc tạo receipt. Health kiểm tra connection và hai bảng, giữ body rỗng; app thiếu database/connection đóng/schema thiếu trả 503. Bootstrap thật vẫn yêu cầu file database và schema trước khi mở port, không tự tạo dữ liệu.

## Snapshot đọc nhất quán

Mỗi response danh sách chỉ có **một SELECT**, chọn đủ các cột và dùng `.all()` để lấy xong mảng trước khi serialize/gửi HTTP. SQLite tự mở read transaction cho statement và giữ snapshot đến khi statement hoàn tất; vì vậy không cần transaction thủ công cho một SELECT này. Xem [SQLite transaction](https://www.sqlite.org/lang_transaction.html) và [SQLite isolation](https://www.sqlite.org/isolation.html).

Đã đối chiếu package/lockfile và module cài đặt: better-sqlite3 **12.11.1**. Theo [API đúng phiên bản](https://github.com/WiseLibs/better-sqlite3/blob/v12.11.1/docs/api.md), all() trả mảng hàng còn get() trả undefined khi không có hàng; lỗi được throw. Source native cài tại `src/objects/statement.cpp` thực thi sqlite3_step đến hết rồi reset trước khi all() trả về. Không dùng iterator HTTP, không await network trong transaction và không dùng BEGIN IMMEDIATE cho API đọc.

Kiểm chứng thực tế trong test: database file tạm WAL với hai connection; writer sửa cả hai task trong transaction chưa commit, reader vẫn đọc toàn bộ bản cũ; sau commit reader đọc toàn bộ bản mới. Reader không giữ transaction sau khi repository trả về. Đây là kiểm tra isolation trước/sau commit, **chưa phải stress test hoặc test ép commit chen giữa lúc một SELECT đang chạy**. Bảo đảm trong một SELECT được đối chiếu với cơ chế SQLite và source driver, không suy ra từ một bài test giả lập.

## Kiểm thử đã chạy

Từ gốc repo:

```bash
cd /Users/tuankhong/Documents/StudyFlow/server
node --version
npm --version
npm test
```

Không cần `.env` hay migrate/seed/reset database demo để chạy test. Nếu thiết lập máy mới, dùng `npm ci` với Node theo package trước. Chạy riêng hai nhóm mới:

```bash
node --test tests/task-repository.test.js
node --test tests/read-api.test.js
npm run test:smoke
```

Ngày **29/10/2026**, Node **v22.14.0**, npm **11.6.4**:

| Nhóm | Số test đạt |
|---|---:|
| Database cũ | 12 |
| Validation | 30 |
| Response/factory | 4 |
| HTTP/error middleware cũ, đã cập nhật kỳ vọng | 10 |
| Repository đọc mới | 8 |
| Integration HTTP đọc mới | 16 |
| **Tổng npm test** | **80/80**, fail 0, skip/cancel 0 |

Mốc Bước 5 có 56/56 test; lần triển khai đầu Bước 6 đạt 77/77. Lần rà soát cả hai bước bổ sung ba test hồi quy và chạy toàn bộ **80/80 đạt**. Ảnh chụp mốc Bước 5 còn được kiểm thử độc lập trong thư mục tạm, 56/56 đạt, để bảo đảm có thể tách commit nhất quán. Test HTTP thực sự mở cổng ngẫu nhiên trên localhost, được chạy ngoài sandbox để có quyền listen. Không dùng framework mới hoặc mock thay kết nối database cho integration test. Test dùng :memory: hoặc file tạm và dọn sau chạy.

Các ca mới gồm default/true/false, query sai/lặp/ngoặc, database rỗng, nhiều task/ID duy nhất, task sống/tombstone, tiếng Việt/null, UUID sai/ID thiếu, lỗi database/serializer, health 200/503 và route ghi chưa tồn tại. Snapshot hàng, migration, receipt và total_changes được so sánh trước/sau GET, bao gồm một receipt fixture trong database test để kiểm tra nó không bị sửa.

## Hai lỗi đã sửa khi rà soát

- Parser query mặc định cắt im lặng sau 1.000 khóa: includeDeleted ở cuối có thể biến thành default true, hoặc giá trị lặp bị bỏ sót. App vẫn dùng kiểu simple query, nhưng parse toàn bộ nội dung request nhận được với maxKeys:0; giới hạn HTTP của Node vẫn áp dụng. Test gửi false ở sau 1.000 khóa vẫn lọc đúng; includeDeleted lặp ở cuối trả 422, dữ liệu không đổi.
- Router gặp URL percent encoding sai tạo URIError trước khi UUID validator chạy; trước đây middleware trả nhầm 500. Chỉ shape lỗi decode của router được nhận diện thành 400 BAD_REQUEST với message cố định `Invalid URL encoding`. URIError/lỗi ứng dụng khác không được tùy ý dùng status client; UUID đã giải mã nhưng sai định dạng vẫn là 422. Test `%ZZ` và `%E0%A4%A` kiểm tra body an toàn và không log nhầm lỗi server.

## Kiểm tra OpenAPI

Đã dùng PyYAML 6.0.2 với loader phát hiện key trùng và script kiểm tra giới hạn: **0 key YAML trùng; 51 local $ref thuộc 19 đích đều resolve; 12 externalValue tồn tại và khớp schema; 13 JSON fixture riêng parse/kiểm tra được**. Default includeDeleted=true và response 422 đã được đối chiếu với validator, truy vấn và HTTP.

Script kiểm tra các từ khóa schema đang dùng ($ref, allOf, oneOf, type, enum, required, nullable, properties, items, độ dài và giới hạn số), UUID dạng chuỗi và ISO date/date-time cho fixture hiện có; không phải kiểm tra RFC đầy đủ. Không tìm thấy OpenAPI validator chuyên dụng đã cài trên máy nên **chưa có kết quả từ validator OpenAPI đầy đủ**; không tự cài dependency mới. Các giới hạn số nguyên an toàn JavaScript đang được validator/serializer áp dụng theo Bước 5; không bổ sung constraint hợp đồng khác ngoài quyết định default includeDeleted đã được yêu cầu.

## Smoke test HTTP hiện tại với database tạm

Chạy từ server/: `npm run test:smoke`. Không cần `.env` hoặc database demo. Script tạo file SQLite trong thư mục tạm, migrate schema của file đó, chèn hai JSON fixture (một task sống và một tombstone), chọn cổng loopback trống rồi khởi động riêng `src/server.js` với DATABASE_PATH tạm. Script chỉ gửi GET, dừng đúng process nó tạo và dọn thư mục sau khi so sánh toàn bộ tasks/receipts/migrations.

Kết quả thực tế của lượt rà soát ngày 29/10/2026: **11 request HTTP đạt**: health/default/true/false/chi tiết sống/chi tiết tombstone đều 200; UUID thiếu 404; ID sai và hai dạng query lỗi 422; URL encoding sai 400. Dữ liệu tạm trước/sau bằng nhau, integrity_check=ok, tiến trình server đã dừng và tài nguyên tạm đã dọn. Lượt này không truy cập hay thay đổi database demo và không chiếm/dừng server khác.

## Ví dụ cURL và bằng chứng lần triển khai ban đầu

Phần sau lưu bằng chứng cURL của lượt triển khai Bước 6 trước lần rà soát hiện tại. Lượt rà soát mới dùng database tạm theo mục trên; không chạy lại các lệnh này trên demo.

Trước chạy đã mở database cấu hình bằng kết nối readonly, không seed/reset: `server/storage/studyflow.db` có **3 task, 1 tombstone, 0 receipt**, integrity_check=`ok`. Sau khi test đạt, đã khởi động riêng `src/server.js` trên **127.0.0.1:3001** (cổng trống) với `.env` hiện có; không dùng tiến trình server khác của người dùng.

Lệnh tương ứng để chạy lại, từ server/:

```bash
PORT=3001 HOST=127.0.0.1 node --env-file=.env src/server.js
```

Giữ server ở terminal đó, gọi ở terminal khác:

```bash
curl -i http://127.0.0.1:3001/api/v1/health
curl -i 'http://127.0.0.1:3001/api/v1/tasks?includeDeleted=true'
curl -i http://127.0.0.1:3001/api/v1/tasks
curl -i 'http://127.0.0.1:3001/api/v1/tasks?includeDeleted=false'
curl -i http://127.0.0.1:3001/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814
curl -i http://127.0.0.1:3001/api/v1/tasks/e5b951a7-797c-42e8-8369-3491c123b1a5
curl -i http://127.0.0.1:3001/api/v1/tasks/6a144198-9c2b-49dc-8c56-cf295b92d016
curl -i http://127.0.0.1:3001/api/v1/tasks/not-a-uuid
curl -i 'http://127.0.0.1:3001/api/v1/tasks?includeDeleted=true&includeDeleted=false'
curl -i http://127.0.0.1:3001/api/v1/not-a-route
```

Lần kiểm tra tự động đã dùng cURL thật với `--write-out` để đọc status và Content-Type của đúng các URL trên. ID chi tiết được lấy từ response danh sách; đã kiểm tra UUID thiếu không nằm trong danh sách trước khi gọi.

| Request | Kết quả thực tế |
|---|---|
| Health | 200, body rỗng, không tự tạo JSON health. |
| Danh sách true | 200, 3 task gồm 1 tombstone. |
| Không truyền includeDeleted | 200, dữ liệu bằng danh sách true. |
| Danh sách false | 200, 2 task sống. |
| Chi tiết ID `612954cb-842b-4d60-a0c8-48a7cc401814` | 200, title “Hoàn thiện API StudyFlow”, dueDate=null, version=1. |
| Chi tiết tombstone `e5b951a7-797c-42e8-8369-3491c123b1a5` | 200, isDeleted=true, version=2. |
| UUID hợp lệ `6a144198-9c2b-49dc-8c56-cf295b92d016` chưa tồn tại | 404 TASK_NOT_FOUND, taskId đúng UUID. |
| ID sai | 422 VALIDATION_ERROR, fieldErrors.id. |
| Query lặp | 422 VALIDATION_ERROR, fieldErrors.includeDeleted. |
| Route sai | 404 ROUTE_NOT_FOUND. |

Các response có JSON đều có `application/json; charset=utf-8`. Sau chạy đã dừng **chính tiến trình vừa khởi động**, exit 0; không dừng tiến trình khác. Không để server kiểm tra chạy nền.

Đã mở lại demo readonly và so checksum nội dung tasks, mutation_receipts, schema_migrations theo cùng thứ tự trước/sau: không đổi; vẫn 3 task, 1 tombstone, 0 receipt và integrity `ok`. Checksum này chỉ là phép kiểm chứng không sửa dữ liệu, không phải logic hash mutation. Không lưu database/runtime/log/cURL payload vào Git.

## Giới hạn và việc tiếp theo

- API đọc đã chạy với database thật; API ghi và idempotency vẫn chưa triển khai.
- Test lỗi connection/schema/serializer dùng database riêng; không cố tình phá database demo để kiểm tra lỗi.
- Chưa kiểm thử commit chen giữa SELECT đang chạy, tải lớn, stress concurrency hoặc hai thiết bị Android với backend thật. Snapshot dùng cơ chế SQLite đã đối chiếu và test hai connection nêu trên.
- Không thêm mã lỗi hạ tầng trong Bước 6; các chính sách INVALID_JSON/INTERNAL_ERROR/ROUTE_NOT_FOUND… từ Bước 5 vẫn cần đối chiếu nếu hai phía muốn đặc tả chi tiết thêm. Default includeDeleted đã được chốt, không còn để ngỏ.
- Bước 7 tiếp tục phần API ghi theo yêu cầu kế tiếp. Khi tới ghi/version/receipt phải giữ thứ tự kiểm tra receipt trước version và ghi task + receipt nguyên tử; không coi API đọc này là đã hỗ trợ retry ghi.

## Kiểm tra và lưu Git

Người dùng đã ủy quyền commit và push nhánh làm việc hiện tại khi rà soát cả Bước 5/6. Không chuyển nhánh, reset, merge vào main hoặc force push. Kiểm tra từ gốc repo:

```bash
cd /Users/tuankhong/Documents/StudyFlow
git diff --check
git status --short --untracked-files=all
git check-ignore server/.env server/node_modules/ server/storage/studyflow.db
```

Đã chạy `git diff --check`, kiểm tra whitespace cả file mới và kiểm tra OpenAPI giới hạn nêu trên: đều đạt. Các file .env/node_modules/database runtime vẫn được ignore; không có thay đổi Android, file log hoặc dữ liệu runtime trong danh sách stage.

Các file Bước 5 chưa commit khi bắt đầu Bước 6. Đã giữ ảnh chụp mốc Bước 5, kiểm thử riêng 56/56 và xác minh patch áp dụng sạch vào index để tách hai commit mà không ghi đè working tree. Commit Bước 6 dựa trên đầy đủ module Bước 5, bao gồm các cập nhật tài liệu và sửa lỗi của lần rà soát; trạng thái cuối đạt 80 test và smoke HTTP tạm.

`git add` theo tên file sẽ stage toàn bộ nội dung hiện tại, không tự tách phần Bước 5/6. Danh sách phạm vi Bước 6 sau khi mốc Bước 5 đã được lưu đầy đủ:

```bash
git add contracts/sync-protocol.md contracts/openapi.yaml contracts/examples/README.md \
  server/src/app.js \
  server/src/routes/read-routes.js \
  server/src/controllers/task-controller.js server/src/controllers/health-controller.js \
  server/src/repositories/task-repository.js server/src/repositories/health-repository.js \
  server/src/validation/task-requests.js server/src/middleware/error-handler.js \
  server/tests/validation.test.js server/tests/http.test.js \
  server/tests/task-repository.test.js server/tests/read-api.test.js \
  server/package.json server/scripts/smoke-read-api.js docs/backend-step-5.md \
  server/README.md docs/backend-step-6.md
git diff --cached --check
git diff --cached
```

Commit đề xuất: `feat(server): triển khai API đọc công việc`.
