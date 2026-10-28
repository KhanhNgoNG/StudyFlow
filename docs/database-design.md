# Thiết kế cơ sở dữ liệu

## Phạm vi Bước 4

Server dùng SQLite qua `better-sqlite3` (phiên bản khóa trong `server/package-lock.json`). Phần này triển khai schema, migration, seed và kiểm thử nền tảng; chưa triển khai repository/service CRUD hoặc retry HTTP. Room trên Android thuộc phần A và không được thay đổi ở bước này.

## Bảng tasks

Tên cột dùng snake_case nội bộ; mapper/API sau này phải trả camelCase đúng hợp đồng.

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

Bảng STRICT chặn kiểu lưu không tương thích, nhưng SQLite có thể ép kiểu không mất dữ liệu. Vì vậy ràng buộc database không thay thế validation JSON chính xác ở bước 5. `trim()` SQLite mặc định xử lý dấu cách; validation sau này cần dùng đúng quy tắc trim của ứng dụng và quyết định độ dài Unicode nhất quán.

Không lưu `baseVersion`, `localRevision`, pending hoặc outbox vào bảng server. `baseVersion` là điều kiện request, không phải thuộc tính task được trả về. Không tạo subject table vì danh sách mã cố định trong hợp đồng.

## Bảng mutation_receipts

| Cột | Vai trò |
|---|---|
| `mutation_id` | UUID khóa chính duy nhất cho mỗi thao tác đã thành công. |
| `task_id` | FK tới tasks; không cascade xóa receipt. |
| `method` | POST, PUT hoặc DELETE. |
| `path` | Đường dẫn request. |
| `request_hash` | SHA-256 dạng hex thường; tầng service sau này phải tạo từ method, path, query và payload chuẩn hóa ổn định, gồm baseVersion. |
| `response_status` | 201 cho POST, 200 cho PUT/DELETE. |
| `response_body` | JSON object được serialize của response thành công đã commit. |
| `created_at` | Thời gian UTC ghi receipt. |

Receipt không tái tạo từ task hiện tại khi replay, vì task có thể đã đổi tiếp. Bảng chỉ chứa kết quả thành công; không dùng làm hàng đợi retry. Giữ receipt và tombstone suốt đợt demo.

## Bảng schema_migrations

Lưu tên file migration, SHA-256 nội dung và thời điểm áp dụng. Runner kiểm tra lịch sử là tiền tố không đổi của danh sách file, rồi áp dụng các file mới theo tên. Cả DDL và ghi lịch sử được bọc transaction; không sửa file đã áp dụng, thêm migration mới cho thay đổi sau.

## Kết nối và transaction

- Đường dẫn tương đối tính từ `server/`; tạo thư mục cha khi migrate. Inspect/seed/reset/start yêu cầu file đã tồn tại.
- Bật foreign key, WAL, synchronous FULL; đợi khóa tối đa 5 giây.
- Migration, seed và reset dùng transaction IMMEDIATE để lấy quyền ghi trước khi đọc điều kiện.
- SQLite chỉ có một writer tại một thời điểm; WAL không cho phép nhiều writer ghi đồng thời.
- Ở bước CRUD sau này, dùng cùng connection/transaction cho: tìm receipt trước version, so sánh hash, kiểm tra version, ghi task và ghi receipt. Có thể dùng UPDATE có điều kiện `WHERE id = ? AND version = ?`, kiểm tra số hàng đổi.
- Callback transaction của driver đồng bộ không được dùng `async` hoặc chứa chờ network.
- GET toàn bộ cần đọc từ snapshot nhất quán; chưa triển khai truy vấn HTTP ở bước này.

Schema tự nó không bảo đảm idempotency: cần service thực hiện đúng thứ tự và transaction ở các bước tiếp theo. Không dùng giờ thiết bị để quyết định phiên bản thắng.

## Seed và reset

Seed gồm 3 task UUID/thời gian cố định, có tiếng Việt, null deadline, hoàn thành và tombstone. Không seed receipt giả vào database demo. Seed chỉ chèn khi cả tasks và receipts rỗng. Nếu mọi ID seed đã có thì bỏ qua toàn bộ, giữ nguyên nội dung/version dù đã sửa. Nếu bộ dữ liệu khác hoặc seed thiếu một phần thì báo lỗi, không tự sửa database.

Reset được gọi rõ ràng bằng CLI với cờ xác nhận ở development/test; xóa receipt trước task trong cùng transaction, giữ schema. Phải reset phối hợp server và hai thiết bị. Không tự reset lúc server khởi động.

## Kiểm chứng

`cd server && npm test` chạy database tạm: migration/seed chạy lại, migration rollback/checksum, khóa ghi và conditional version, UNIQUE/CHECK, rollback task/receipt, reset có bảo vệ, không trộn seed và persistence bằng tiến trình khác. Các test này chưa thay thế test API/concurrency/retry đầy đủ trong các bước tiếp theo.

Xem [hướng dẫn Bước 4](backend-step-4.md) và [README server](../server/README.md) để chạy thực tế.
