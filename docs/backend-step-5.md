# Bước 5 backend: validation và chuẩn hóa response

## Mục tiêu và phạm vi đã làm

Bước này tạo các thành phần dùng lại để kiểm tra request, chuyển hàng SQLite thành task JSON, bọc response và xử lý lỗi Express. Riêng mốc Bước 5 không thêm dependency, SQL nghiệp vụ, migration hoặc endpoint task. Trong trạng thái hiện tại sau Bước 6, app chính đã có GET health/danh sách/chi tiết; POST/PUT/DELETE và service idempotency vẫn chưa triển khai. Xem [Bước 6](backend-step-6.md) về API đọc và kết quả rà soát mới nhất.

Đã đọc [giao thức đồng bộ](../contracts/sync-protocol.md), [OpenAPI](../contracts/openapi.yaml), toàn bộ [JSON mẫu](../contracts/examples/README.md), [thiết kế database](database-design.md), README và mã server hiện tại trước khi sửa. Nhánh lúc bắt đầu: `codex/backend-api-contract`, working tree sạch. Không thay đổi Android hoặc các file hợp đồng đã chốt.

## File tạo và sửa

| File | Vai trò |
|---|---|
| Tạo `server/src/validation/task-fields.js` | Kiểu dữ liệu và quy tắc chung: UUID, enum, ngày thực/năm nhuận, Unicode, boolean, số nguyên an toàn. |
| Tạo `server/src/validation/task-requests.js` | Validator POST/PUT/DELETE, ID và includeDeleted. Tạo dữ liệu mới, giữ nguyên request đầu vào. |
| Tạo `server/src/http/task-responses.js` | Mapper `taskFromRow`, envelope `taskResponse`/`taskListResponse`; lỗi dữ liệu DB không bị che bằng default. |
| Tạo `server/src/http/api-errors.js` | Lớp ApiError và factory validation, task không tồn tại, version conflict, mutation ID reused. |
| Tạo `server/src/middleware/validate-task-request.js` | Adapter nối request Express với validator, đặt kết quả ở `res.locals.validatedRequest`. |
| Tạo `server/src/middleware/error-handler.js` | Route 404 và error middleware trả JSON an toàn, ghi log chẩn đoán. |
| Sửa `server/src/app.js` | JSON parser `strict: false`, route 404 và error handler sau health; hỗ trợ truyền logger khi test. |
| Tạo `server/tests/validation.test.js` | 30 test validator, trong đó đối chiếu độ dài Unicode với SQLite thật trong bộ nhớ. |
| Tạo `server/tests/responses.test.js` | 4 test mapper/envelope/factory so với fixture hợp đồng và hàng SQLite thật. |
| Tạo `server/tests/http.test.js` | 10 test HTTP/error middleware, app test riêng và health của app chính. |
| Sửa `server/README.md` | Cấu trúc hiện tại, cách chạy test, validation, chính sách lỗi và giới hạn thực tế. |
| Tạo `docs/backend-step-5.md` | Hướng dẫn này. |

Các file database và 12 test database cũ giữ nguyên. Không thay package/lockfile vì Express, `node:test`, `node:assert`, `fetch` của Node 22 và better-sqlite3 hiện có đã đủ.

## Những khái niệm mới

**Validation** là kiểm tra dữ liệu trước khi cho nghiệp vụ dùng. Ví dụ `false` là boolean JSON hợp lệ; chuỗi `"false"` là string nên phải bị từ chối. Việc SQLite có thể ép một số kiểu không thay thế được kiểm tra này.

**Mapper/serializer** chuyển dữ liệu lưu trữ sang cấu trúc gửi qua HTTP: `subject_code` thành `subjectCode`, `is_deleted: 0` thành `isDeleted: false`. Serializer chỉ chọn trường hợp đồng, không đưa toàn bộ hàng database lên mạng.

**Envelope** là object bọc kết quả: một task dùng `{"task": ...}`, danh sách dùng `{"tasks": [...]}`, rỗng dùng `{"tasks": []}`. Nhờ cấu trúc cố định, Android biết cần đọc ở đâu.

**Middleware** là một bước trong chuỗi xử lý Express. Middleware validation cho request hợp lệ đi tiếp bằng `next()`; nếu có lỗi thì gọi `next(error)`. **Factory lỗi** là hàm tạo lỗi có status/body đúng cấu trúc; việc có hàm tạo 409 chưa có nghĩa service đã kiểm tra xung đột.

## Luồng request và response

Luồng các thành phần đã chuẩn bị cho API task:

```text
HTTP request
  → express.json({ strict: false }): giải mã cú pháp JSON
  → adapter validation: lấy body, params, query, Idempotency-Key
  → validator: kiểm tra, trả object mới; chỉ trim title
  → res.locals.validatedRequest
  → controller/repository đọc (đã có ở Bước 6; nghiệp vụ ghi chưa triển khai)
  → hàng SQLite
  → taskFromRow: kiểm tra dữ liệu + đổi tên/boolean
  → taskResponse hoặc taskListResponse
  → res.status(...).json(...)
```

`validateCreateTaskRequest({ body, idempotencyKey })` trả `{ task, baseVersion, mutationId }`. PUT thêm `id` của đường dẫn và trả cùng cấu trúc. DELETE nhận `{ id, baseVersion, idempotencyKey }`, trong đó baseVersion đầu vào là chuỗi query; kết quả baseVersion là number. `validateTaskId(id)` dùng cho GET chi tiết. `validateListTasksQuery(query)` trả `{ includeDeleted: true/false }`; không gửi tham số trả `{ includeDeleted: true }` theo quyết định chính thức ở Bước 6.

Các adapter GET `validateGetTask`, `validateListTasks` đã được app chính dùng từ Bước 6. Adapter POST/PUT/DELETE mới được gắn vào app riêng trong `http.test.js`; handler test trả lại dữ liệu đã kiểm tra để quan sát middleware, không lưu task. App chính chưa có route ghi hoặc route phục vụ test.

Khi làm bước receipt, **không đặt validation đầy đủ này trước việc tìm/replay receipt**. Theo hợp đồng, cần kiểm tra định danh mutation/cấu trúc cần thiết, tìm receipt và so request gốc trước version/validation nghiệp vụ: cùng mutation khác nội dung phải thành MUTATION_ID_REUSED; retry thành công phải nhận lại response cũ. Các validator không sửa `req.body`/query gốc và chưa tính hash. Chưa có scheduler, SyncEngine hoặc cơ chế idempotency mới ở backend.

## Quy tắc request đã kiểm tra

- POST/PUT phải gửi đủ id, title, description, subjectCode, dueDate, priority, completed, isDeleted và baseVersion. Không dùng giá trị mặc định thay trường thiếu. dueDate bắt buộc xuất hiện nhưng được null; các trường khác không được null.
- Title trim rồi dài 1–120 code point; description là string dài 0–2000. Không trim description.
- Enum giữ đúng chữ hoa theo hợp đồng. completed/isDeleted phải là boolean thật; POST/PUT chỉ nhận isDeleted=false.
- Ngày kiểm tra cả định dạng và số ngày của tháng, kể cả quy tắc năm chia hết 4/100/400. Không dùng Date tự cuộn ngày 30/02 thành tháng 3; không gắn múi giờ vào dueDate.
- POST baseVersion là number nguyên an toàn bằng 0; PUT là number nguyên an toàn >= 1. Đây là kiểm tra cấu trúc; việc task tồn tại/version khớp vẫn thuộc service sau này.
- ID body PUT phải khớp nguyên chuỗi ID URL. ID đường dẫn và Idempotency-Key phải là UUID dạng 8-4-4-4-12 hex; không giới hạn chỉ UUID v4, không đổi chữ hoa/thường.
- DELETE baseVersion chỉ nhận một chuỗi không rỗng gồm chữ số ASCII, rồi kiểm tra 1–9007199254740991. Không nhận dấu, khoảng trắng, thập phân, số mũ, hậu tố, query lặp thành array hoặc số vượt giới hạn an toàn. Chuỗi `01` được hiểu là số 1; request gốc được giữ nguyên.
- includeDeleted chỉ nhận chuỗi `true` hoặc `false`, không ép kiểu bằng Boolean. Thiếu tham số mặc định true; query lặp hoặc kiểu bất thường trả 422. App đọc toàn bộ query trong giới hạn HTTP, không cắt im lặng sau 1.000 khóa khiến bỏ sót flag hoặc giá trị lặp ở cuối.
- Validator chỉ đưa các trường nghiệp vụ vào task kết quả. version, serverUpdatedAt và metadata ngoài whitelist bị bỏ khỏi dữ liệu ghi; không bị lấy để lưu. Chưa có ghi database qua HTTP.

Thông báo fieldErrors phân biệt `is required`, `must not be null`, `must be a ...` và giới hạn giá trị. Nhiều trường sai được gom trong một response. Tên trường là camelCase, header dùng khóa `Idempotency-Key`, lỗi root dùng `body`. Đây là thông báo cho lập trình viên; Android ánh xạ code/tên trường sang nhãn tiếng Việt.

## Quyết định triển khai và điểm hợp đồng chưa chốt

Các chi tiết Unicode và lỗi hạ tầng dưới đây được ghi rõ như quyết định triển khai Bước 5, chưa được hợp đồng mô tả đầy đủ. Riêng default includeDeleted đã được người dùng xác nhận chính thức ở Bước 6 và cập nhật hợp đồng; không còn là điểm để ngỏ.

| Điểm cần làm rõ | Hành vi hiện tại và ảnh hưởng |
|---|---|
| “Ký tự” là code unit, code point hay ký tự nhìn thấy? | Dùng Unicode code point theo SQLite `length(TEXT)`: 😀 = 1; `a` + dấu sắc tách rời = 2; emoji gia đình 👨‍👩‍👧‍👦 = 7. Không dùng `.length` UTF-16 và không đếm grapheme. Android cần đối chiếu bộ đếm ở biên giới hạn. |
| Trim gồm những khoảng trắng nào? | Dùng `String.trim()` của JavaScript: cả space, tab, newline, NBSP… ở hai đầu. SQLite trim mặc định chỉ bỏ space nên request phải được trim trước khi lưu. Không normalize NFC/NFD, không tự đổi dạng tiếng Việt. |
| NUL / surrogate đơn trong JSON | Từ chối title/description có U+0000 hoặc surrogate không ghép cặp, trả 422 VALIDATION_ERROR. SQLite length dừng ở NUL; surrogate đơn không phải chuỗi Unicode hợp lệ cho trao đổi. Không âm thầm cắt hay thay ký tự. Đây là hạn chế dữ liệu được nêu rõ để hai phía thống nhất. |
| Khoảng năm của YYYY-MM-DD | Nhận bốn chữ số 0000–9999 theo lịch Gregory kéo dài và tương thích SQLite, gồm năm 0000. Không tự giới hạn “ngày tương lai” hoặc lịch học. Nếu Android giới hạn khác cần chốt chung trước tích hợp. |
| Trường lạ/server-managed do client gửi | OpenAPI không có additionalProperties=false; dùng whitelist, bỏ khỏi kết quả thay vì tự thêm quy tắc reject trường lạ. Giữ request gốc cho bước receipt. |
| includeDeleted vắng mặt — đã chốt | Mặc định true, trả cả task sống/tombstone; false chỉ trả task sống. OpenAPI có default:true; query sai/lặp trả 422. Đây là bổ sung hành vi mặc định đã được người dùng xác nhận, không đổi các quy tắc đồng bộ khác. Android vẫn gửi rõ true. |
| Body lỗi hạ tầng | Chọn các status/code trong bảng dưới để có JSON nhất quán; chưa thêm chúng vào OpenAPI/JSON mẫu vì hợp đồng chưa chốt chi tiết này. Phía Android cần biết đây không phải các code nghiệp vụ đã thống nhất. |

Quy tắc code point phù hợp với [tài liệu SQLite về length](https://www.sqlite.org/lang_corefunc.html#length); test còn chạy truy vấn length thật trên connection `:memory:`. NUL là ngoại lệ được chặn rõ ràng.

## Serializer và kiểm tra dữ liệu server

`taskFromRow(row)` trả đúng 10 trường của Task. Chỉ SQLite number 0/1 được đổi thành boolean; không dùng Boolean để chấp nhận `"0"`, null hoặc số khác. due_date phải là null thật hoặc ngày hợp lệ; thiếu cột hoặc chuỗi rỗng không bị đổi thành null. version phải là số nguyên an toàn >= 1; server_updated_at phải là ngày/giờ UTC có T và Z, có thể có phần lẻ giây.

Bản ghi sai enum, ngày, version, text hoặc thiếu trường gây TypeError; bản ghi title chưa trim cũng bị từ chối, không bị sửa trong serializer. Lỗi đó đi thành 500 vì dữ liệu server sai, không đổ thành lỗi validation của client. Danh sách có một hàng sai thất bại toàn bộ, không trả phần dữ liệu còn lại như thể đọc thành công.

## Luồng lỗi và logging

```text
Validator phát hiện dữ liệu sai → ApiError(422, body)
Factory 404/409 được service gọi sau này → ApiError(status, body)
Parser JSON lỗi hoặc handler/serializer throw/reject → Express bắt lỗi
  → error middleware cuối chuỗi
  → lỗi đã biết: status/body cố định
  → lỗi bất ngờ: log chẩn đoán trên server → 500 INTERNAL_ERROR
```

Lỗi nghiệp vụ đã có cấu trúc đúng hợp đồng:

| Lỗi | Nội dung |
|---|---|
| 422 VALIDATION_ERROR | code, message, fieldErrors; taskId chỉ khi xác định được UUID. POST lấy từ body, PUT/DELETE ưu tiên đường dẫn. |
| 404 TASK_NOT_FOUND | code, message, taskId; GET chi tiết Bước 6 đã dùng khi UUID hợp lệ không có hàng trong database. |
| 409 VERSION_CONFLICT | code, message, taskId, currentTask đầy đủ được mapper kiểm tra từ hàng SQLite; chưa kiểm tra version thực tế. |
| 409 MUTATION_ID_REUSED | code, message, taskId nếu biết; chưa đọc/tạo/replay receipt. |

Các lỗi hạ tầng bổ sung có chủ đích, ngoài phần hợp đồng đang mô tả:

| Tình huống | Status / code / message |
|---|---|
| JSON không parse được | 400 / INVALID_JSON / Malformed JSON body |
| Vượt giới hạn parser mặc định 100kb | 413 / PAYLOAD_TOO_LARGE / Request body too large |
| Content-Type sai ở validator POST/PUT | 415 / UNSUPPORTED_MEDIA_TYPE / Content-Type must be application/json |
| Encoding/charset parser không hỗ trợ | 415 / UNSUPPORTED_MEDIA_TYPE / Unsupported request encoding |
| Body bị hủy hoặc sai Content-Length do parser báo | 400 / BAD_REQUEST / Invalid request body |
| Router không giải mã được URL | 400 / BAD_REQUEST / Invalid URL encoding |
| URL/method không có route | 404 / ROUTE_NOT_FOUND / Route not found |
| Lỗi bất ngờ | 500 / INTERNAL_ERROR / Internal server error |

Status parser 400/413/415 theo hành vi [body-parser của Express](https://expressjs.com/en/resources/middleware/body-parser/#errors). Các **tên code và message** trong bảng là lựa chọn triển khai này. JSON hợp lệ nhưng root null/array/string/number/boolean được parser đọc với strict=false rồi validator từ chối bằng 422. Body 0 byte có thể được Express đọc thành `{}`; khi đó các trường bắt buộc đều báo thiếu, không được điền default.

Không dùng TASK_NOT_FOUND cho URL sai. GET `/api/v1/tasks` hiện đã hoạt động; URL/method chưa triển khai trả ROUTE_NOT_FOUND. ID đã giải mã nhưng không phải UUID trả 422; URL có percent encoding sai như `%ZZ` không đến được validator nên trả 400 BAD_REQUEST với message cố định. Chỉ nhận diện URIError theo shape lỗi router thực tế, không tin status tùy ý trên mọi lỗi. Parser chạy trước route nên JSON sai cú pháp có thể trả 400 ngay cả ở URL chưa triển khai.

Lỗi bất ngờ không lấy status/expose/message từ lỗi thư viện để trả client; response chỉ có code/message cố định. Logger mặc định là console.error, có thể truyền logger khi tạo app. Log gồm giờ UTC, method, mẫu route, tên lỗi, mã SQLITE_/ERR_ nếu phù hợp và các vị trí trong stack. Không log body, header, query hoặc nguyên error.message có thể chứa SQL/payload. Đường dẫn ở stack phục vụ chẩn đoán tại server, không xuất hiện trong response. Không tạo file log cá nhân.

Nếu response đã gửi header, middleware chuyển lỗi tiếp theo hướng dẫn [Express error handling](https://expressjs.com/en/guide/error-handling/#the-default-error-handler); lúc này không thể thay body/status đã gửi. Không có handler streaming mới trong Bước 5. Health giữ xử lý riêng: schema truy vấn được trả 200 rỗng, truy vấn lỗi trả 503 rỗng.

## Cách chạy test và kết quả đã chạy

Từ gốc repo, với dependency hiện có (hoặc sau `npm ci` khi thiết lập máy mới):

```bash
cd /Users/tuankhong/Documents/StudyFlow/server
node --version
npm --version
npm test
```

Không cần `.env`, `db:migrate`, `db:seed` hoặc reset demo trước khi chạy test. Chạy riêng từng nhóm để tìm lỗi:

```bash
node --test tests/validation.test.js
node --test tests/responses.test.js
node --test tests/http.test.js
node --test tests/database.test.js
```

Kết quả lịch sử của mốc Bước 5: **56/56 đạt**, gồm 12 database + 30 validation + 4 response/factory + 10 HTTP/error middleware. Khi rà soát để tách commit, ảnh chụp mốc này đã được chạy lại trong thư mục tạm và vẫn đạt 56/56. Kết quả hiện tại sau Bước 6 và sửa lỗi, ngày **29/10/2026**, Node **v22.14.0**, npm **11.6.4**: **80/80 đạt**, fail/skip/cancel đều 0. Chi tiết 24 test repository/API đọc và smoke test database tạm nằm ở [Bước 6](backend-step-6.md).

Trong lần chạy ở sandbox, 9 test cần listen localhost bị EPERM, 47 test khác đạt. Sau khi được phép chạy ngoài sandbox, HTTP thật đã chạy; phát hiện và sửa kỳ vọng test body rỗng cho đúng cách Express trả `{}`. Lần chạy toàn bộ sau sửa đạt 56/56. Đây là lỗi môi trường và kỳ vọng test đã được giải quyết, không phải kết quả HTTP được giả lập.

Test database cũ dùng file tạm có dọn; test mới dùng `:memory:` hoặc dữ liệu fixture. HTTP test mở port ngẫu nhiên trên loopback và tự đóng. Không ghi, inspect, reset hoặc seed lại database demo trong quá trình Bước 5.

API đọc đã được kiểm chứng ở Bước 6. Chưa triển khai API ghi, kiểm tra version nguyên tử, hash/receipt/retry hoặc ghép đồng bộ Android với backend thật. Các nhánh parser request.aborted/request.size.invalid được xử lý nhưng chưa có test socket ngắt giữa chừng/Content-Length sai; HTTP đã kiểm tra JSON sai cú pháp, quá dung lượng, encoding không hỗ trợ và URL encoding sai.

## Kiểm tra Git và chuẩn bị commit

Từ gốc repo:

```bash
cd /Users/tuankhong/Documents/StudyFlow
git diff --check
git status --short --untracked-files=all
git check-ignore server/.env server/node_modules/ server/storage/studyflow.db
```

Chỉ stage các file của Bước 5 sau khi bạn xem diff:

```bash
git add server/src/app.js \
  server/src/validation/task-fields.js \
  server/src/validation/task-requests.js \
  server/src/http/task-responses.js \
  server/src/http/api-errors.js \
  server/src/middleware/validate-task-request.js \
  server/src/middleware/error-handler.js \
  server/tests/validation.test.js \
  server/tests/responses.test.js \
  server/tests/http.test.js \
  server/README.md \
  docs/backend-step-5.md
git diff --cached --check
git diff --cached
```

Thông điệp mốc Bước 5: `feat(server): bổ sung validation và chuẩn hóa response`. Người dùng đã ủy quyền commit/push khi rà soát cả hai bước; mốc Bước 5 được tách từ ảnh chụp đã kiểm thử riêng, không reset working tree hoặc chuyển nhánh. Không merge vào main hoặc force push.

## Trạng thái sau Bước 6

GET danh sách/chi tiết và health đã hoạt động. Default includeDeleted=true đã được xác nhận và ghi vào Sync Protocol/OpenAPI. Bước tiếp theo là phần ghi theo yêu cầu Bước 7; khi tới version/receipt, giữ đúng thứ tự receipt trước version và transaction task + receipt. Chưa triển khai phần ghi trong lần rà soát này.
