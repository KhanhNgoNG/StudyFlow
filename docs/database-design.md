# Thiết kế cơ sở dữ liệu

## Phạm vi hiện tại

Server dùng SQLite qua `better-sqlite3` 12.11.1 (khóa trong `server/package-lock.json`). Bước 4 tạo schema/migration/seed; Bước 5–6 thêm validation, serializer và API đọc. Bước 7 thêm executor mutation dùng chung và receipt bền vững; chưa nối các endpoint ghi hoặc logic CRUD/version cụ thể của Bước 8. Không thay đổi Room hoặc mã Android.

## Bảng tasks

Tên cột dùng snake_case nội bộ; mapper/API hiện trả camelCase đúng hợp đồng.

| Cột SQLite | Trường API | Kiểu và ràng buộc |
|---|---|---|
| `id` | `id` | TEXT, khóa chính UUID có kiểm tra dạng 8-4-4-4-12 ký tự hex. |
| `title` | `title` | TEXT, độ dài trim 1–120. |
| `description` | `description` | TEXT, tối đa 2.000, mặc định rỗng. |
| `subject_code` | `subjectCode` | MOBILE, DATABASE, GENERAL, OTHER. |
| `due_date` | `dueDate` | TEXT YYYY-MM-DD hợp lệ hoặc NULL. |
| `priority` | `priority` | LOW, MEDIUM, HIGH; mặc định MEDIUM. |
| `completed` | `completed` | INTEGER 0/1; mapper phải trả boolean JSON. |
| `is_deleted` | `isDeleted` | INTEGER 0/1; bản xóa vẫn giữ ID và nội dung. |
| `version` | `version` | INTEGER >= 1, server quản lý ở tầng service sau này. |
| `server_updated_at` | `serverUpdatedAt` | TEXT thời gian UTC có T và Z, SQLite đọc được. |

Bảng STRICT chặn kiểu lưu không tương thích, nhưng SQLite có thể ép kiểu không mất dữ liệu. Ràng buộc database không thay thế validation JSON Bước 5: title dùng String.trim(), đếm Unicode code point như SQLite length; từ chối NUL/surrogate đơn, không normalize Unicode. Fingerprint Bước 7 lấy nội dung trước bước trim/validation nghiệp vụ này.

Không lưu `baseVersion`, `localRevision`, pending hoặc outbox vào bảng server. `baseVersion` là điều kiện request, không phải thuộc tính task được trả về. Không tạo subject table vì danh sách mã cố định trong hợp đồng.

## Bảng mutation_receipts

| Cột | Vai trò |
|---|---|
| `mutation_id` | UUID khóa chính duy nhất cho mỗi thao tác đã thành công. |
| `task_id` | FK tới tasks; không cascade xóa receipt. |
| `method` | POST, PUT hoặc DELETE. |
| `path` | Đường dẫn request. |
| `request_hash` | SHA-256 gồm 64 ký tự hex thường, từ method/path/toàn bộ query/payload JSON gốc đã canonicalize; gồm baseVersion đúng vị trí. |
| `response_status` | 201 cho POST, 200 cho PUT/DELETE. |
| `response_body` | JSON object được serialize của response thành công đã commit. |
| `created_at` | Thời gian UTC ghi receipt. |

Receipt không tái tạo từ task hiện tại khi replay, vì task có thể đã đổi tiếp. Bảng chỉ chứa kết quả thành công; không dùng làm hàng đợi retry. Giữ receipt và tombstone suốt đợt demo.

## Dấu nhận diện request ở Bước 7

`src/mutations/request-fingerprint.js` tạo canonical JSON và SHA-256 từ `{ method, path, query, body }`. Đây là quy ước kỹ thuật để so sánh cùng một request, không thay đổi endpoint/trường/enum hay chính sách đồng bộ:

- Dữ liệu đầu vào là JSON đã parse, chưa trim hoặc ép kiểu theo nghiệp vụ; không hash nguyên byte HTTP. Whitespace ngoài JSON/thứ tự key không mang ý nghĩa. Không dùng JSON.stringify trực tiếp trên object để phụ thuộc vào thứ tự key ban đầu.
- Sort mọi key object đệ quy theo thứ tự UTF-16 của JavaScript, kể cả object trong array và key giống số; serialize trực tiếp nên key `__proto__` không làm đổi prototype. Giữ nguyên thứ tự array, chuỗi, Unicode và dấu của số -0; số hữu hạn khác dùng biểu diễn JSON của JavaScript. Độ chính xác số là giá trị JSON đã parse của Node, không tái tạo cách viết số trên wire.
- Phân biệt thiếu trường, null, false, 0 và string. Body thiếu hoặc root undefined là không có JSON payload; khác body:null hoặc body:{}. Undefined bên trong payload, số không hữu hạn, BigInt, function, symbol, getter/toJSON thực thi, cycle và sparse array bị từ chối bằng validation error cấu trúc; không âm thầm bỏ/ép thành null.
- Query được nhận dưới dạng object chuỗi HTTP; sort tên tham số, giữ giá trị chuỗi gốc và thứ tự array khi query lặp. Query thiếu là `{}`, khác query:null không hợp lệ. Không dùng parseInt/coerce baseVersion trước hash: `baseVersion=01` và `baseVersion=1` có hash khác nhau.
- Mọi query và trường payload đều có trong hash, kể cả phần validator không dùng để ghi. Idempotency-Key nằm ở khóa receipt, không nằm trong hash. Không thêm timestamp hiện tại, random, localRevision hoặc metadata Android vào dấu nhận diện.

Minimum check chỉ kiểm tra UUID Idempotency-Key, method POST/PUT/DELETE, path HTTP tuyệt đối và dữ liệu query/JSON có thể nhận diện. Sau khi lookup receipt mới chạy validation đầy đủ: POST/PUT không hỗ trợ query; DELETE chỉ hỗ trợ baseVersion, từ chối body JSON và query lặp/sai kiểu. Vì vậy cùng key với query lạ hoặc payload khác vẫn được nhận diện là MUTATION_ID_REUSED trước khi lỗi nghiệp vụ có thể che nó thành 422. Mutation mới có query chưa hỗ trợ nhận 422, không bị bỏ qua tham số.

## Executor, snapshot và transaction

`createMutationExecutor(database).execute(input, apply)` dùng connection được truyền vào cho toàn bộ thao tác. `input` có method, path, query, body và idempotencyKey; không dùng object đã trim title. Thứ tự:

1. Kiểm tra nhận diện tối thiểu và tạo fingerprint, sao chép request JSON để callback không đổi dấu nhận diện.
2. Mở transaction IMMEDIATE, tìm mutation_receipts theo mutation ID.
3. Receipt hợp lệ cùng hash/method/path/task ID: trả status và body snapshot đã lưu; không gọi callback, không kiểm tra version hoặc đọc task hiện tại để dựng response. Khác hash: 409 MUTATION_ID_REUSED.
4. Chưa có receipt: dùng validators Bước 5, gọi callback đồng bộ `apply({ database, request })`. request là dữ liệu đã validation; logic tồn tại/version/CRUD cụ thể do callback Bước 8 cung cấp.
5. Callback trả `{ status, body }`: POST phải 201, PUT/DELETE phải 200; body đúng một envelope task đủ trường, đúng UUID/trạng thái xóa. Serializer assertion dùng lại quy tắc Bước 5. Snapshot mới phải khớp hàng thực sự được lưu trên cùng connection trước khi ghi receipt; không cho callback đổi task ID gốc.
6. Serialize body một lần thành JSON TEXT, ghi đủ tám cột receipt rồi COMMIT. Executor chỉ trả `{ status, body, replayed }` sau khi driver commit xong; replayed là metadata nội bộ, không nằm trong HTTP body/receipt body.

Response mới và replay đều trả object JSON, không bọc envelope lần nữa hoặc trả string JSON. Mỗi lần trả có object snapshot riêng; sửa object ở caller không làm đổi receipt. Receipt sai JSON, schema, hash format, timestamp, method/path/status hoặc task ID gây lỗi server và không chạy lại nghiệp vụ để sửa dữ liệu.

Executor từ chối chạy trong transaction đang mở: transaction lồng của better-sqlite3 dùng savepoint, không đủ để cam kết outer COMMIT. Callback phải là hàm đồng bộ: AsyncFunction kể cả bind bị chặn trước invocation; Promise/thenable trả về bị rollback. Đây là callback nội bộ đáng tin cậy, không phải sandbox chạy code tùy ý: callback không được tự COMMIT/ROLLBACK, mở giao dịch trên connection khác, dùng async wrapper, timer, Promise continuation hoặc chờ network để ghi sau khi trả về. Các guard không thay thế quy tắc này.

Lỗi callback/validation/receipt INSERT/COMMIT làm rollback task và receipt. Không lưu receipt cho lỗi 404/409/422 hay lỗi tạm thời. BEGIN IMMEDIATE lấy quyền ghi trước SELECT receipt, nên hai writer cùng key không thể cùng thấy chưa có receipt rồi cùng ghi. Nếu hết busy_timeout, SQLITE_BUSY/LOCKED được giữ nguyên để error middleware hiện có trả 500 INTERNAL_ERROR an toàn (nhóm 5xx tạm thời); không đổi thành VERSION_CONFLICT, không tự retry vô hạn. Lần retry sau commit đọc receipt từ SQLite, không cần cache trong RAM.

## Bảng schema_migrations

Lưu tên file migration, SHA-256 nội dung và thời điểm áp dụng. Runner kiểm tra lịch sử là tiền tố không đổi của danh sách file, rồi áp dụng các file mới theo tên. Cả DDL và ghi lịch sử được bọc transaction; không sửa file đã áp dụng, thêm migration mới cho thay đổi sau.

## Kết nối và transaction

- Đường dẫn tương đối tính từ `server/`; tạo thư mục cha khi migrate. Inspect/seed/reset/start yêu cầu file đã tồn tại.
- Bật foreign key, WAL, synchronous FULL; đợi khóa tối đa 5 giây.
- Migration, seed và reset dùng transaction IMMEDIATE để lấy quyền ghi trước khi đọc điều kiện.
- SQLite chỉ có một writer tại một thời điểm; WAL không cho phép nhiều writer ghi đồng thời.
- Executor Bước 7 giữ cùng connection/transaction cho receipt, callback ghi task và receipt thành công. Bước 8 nối kiểm tra version vào callback; có thể dùng UPDATE điều kiện `WHERE id = ? AND version = ?`, kiểm tra số hàng đổi.
- Callback transaction của driver đồng bộ không được dùng `async` hoặc chứa chờ network.
- GET toàn bộ Bước 6 dùng một SELECT .all() để có snapshot đọc nhất quán; không lấy khóa IMMEDIATE cho GET.

Schema tự nó không bảo đảm idempotency; executor hiện giữ thứ tự và transaction nêu trên. Không dùng giờ thiết bị để quyết định phiên bản thắng. Chưa có endpoint ghi thật cho Android gọi.

## Seed và reset

Seed gồm 3 task UUID/thời gian cố định, có tiếng Việt, null deadline, hoàn thành và tombstone. Không seed receipt giả vào database demo. Seed chỉ chèn khi cả tasks và receipts rỗng. Nếu mọi ID seed đã có thì bỏ qua toàn bộ, giữ nguyên nội dung/version dù đã sửa. Nếu bộ dữ liệu khác hoặc seed thiếu một phần thì báo lỗi, không tự sửa database.

Reset được gọi rõ ràng bằng CLI với cờ xác nhận ở development/test; xóa receipt trước task trong cùng transaction, giữ schema. Phải reset phối hợp server và hai thiết bị. Không tự reset lúc server khởi động.

## Kiểm chứng

`cd server && npm test` đã chạy thật **116/116 đạt** ngày 29/10/2026: 80 test Bước 4–6, 12 fingerprint, 21 executor và 3 HTTP mutation. Không dùng database demo. Bao gồm rollback callback/INSERT receipt, lỗi COMMIT do deferred FK, mở lại connection, receipt hỏng và HTTP cố ý mất response sau commit rồi retry snapshot cũ.

Concurrency thực tế dùng hai Worker và hai connection trên file SQLite tạm WAL: writer giữ khóa bằng gate đồng bộ chỉ dành cho test, contender nhận SQLITE_BUSY trước commit và callback chưa chạy; sau khi writer commit, test cho contender retry cùng key, nhận replay và không gọi callback. Cuối cùng chỉ có một task/receipt, version 1. Đây là contention + retry thật; chưa phải stress test hoặc hai HTTP request ghi vào app chính. Production callback không chứa gate hoặc chờ network; parent test không bị chặn event loop.

Xem [hướng dẫn Bước 4](backend-step-4.md) và [README server](../server/README.md) để chạy thực tế.
