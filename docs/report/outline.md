# Đề cương báo cáo và nội dung backend

Nội dung này bổ sung vào Markdown báo cáo hiện có; chưa phải báo cáo cuối để nộp. Phần Android do A hoàn thiện và xác nhận. Không có Word/PDF mới hoặc số liệu giả trong Bước 13.

## 1. Bài toán, phạm vi và căn cứ

StudyFlow quản lý công việc học tập offline, dùng chung dataset server giữa hai thiết bị, không có tài khoản. Mục tiêu backend là lưu bền vững, kiểm soát ghi đè và trả lại kết quả đã commit khi client gửi lại. Yêu cầu hiện hành ở [requirements](../requirements.md) và [Sync Protocol](../../contracts/sync-protocol.md).

Đặc tả dự án phân biệt Kotlin/Room/ViewModel/Repository/Retrofit/Coroutines/Flow/WorkManager theo yêu cầu đề với Compose/Material 3/Node/Express/SQLite server do nhóm chọn. PDF đề gốc được DOCX dẫn tới chưa có trong repo, vì vậy cả nhóm cần xác nhận yêu cầu chấm/nộp từ bản gốc; tài liệu này không tự tuyên bố đã đọc PDF đó.

## 2. Lý thuyết phục vụ backend và đồng bộ (D1)

Offline-first cần nguồn local để UI đọc được khi mạng lỗi; ghi local và snapshot chờ gửi bền vững trước HTTP là phần Android. Queue/retry phải giữ cùng định danh thao tác vì timeout chỉ nói client chưa nhận được kết quả. Hướng dẫn [Android offline-first](https://developer.android.com/topic/architecture/data-layer/offline-first) là căn cứ lý thuyết, không là bằng chứng app đã thực hiện.

Backend dùng optimistic concurrency: request mang baseVersion, server chỉ ghi khi khớp bản hiện tại. Hai client từ cùng version không được ghi đè im lặng. Timestamp thiết bị không đáng dùng để chọn thắng vì có thể lệch; serverUpdatedAt chỉ mô tả bản được server lưu. Retry dùng receipt thay vì suy lại từ task mới nhất. Các chính sách này là quyết định dự án.

Tính nguyên tử transaction ngăn task thành công nhưng thiếu receipt. SQLite chỉ cho một writer; BEGIN IMMEDIATE chiếm quyền ghi từ đầu, có thể báo SQLITE_BUSY trước callback. [SQLite transactions](https://www.sqlite.org/lang_transaction.html) và [better-sqlite3 v12.11.1](https://github.com/WiseLibs/better-sqlite3/blob/v12.11.1/docs/api.md) giải thích cơ chế; concurrency test của repo là bằng chứng triển khai cụ thể. MVVM/Room/Retrofit/Flow/WorkManager chi tiết và phiên bản phía Android cần A bổ sung; không viết thay thành kết quả đã tích hợp. [Danh mục nguồn](../references.md) nêu phạm vi từng tài liệu.

## 3. Lựa chọn công nghệ

| Lựa chọn StudyFlow | Lý do và đánh đổi |
|---|---|
| Node 22, JavaScript ES modules | Cùng ngôn ngữ cho route/service/test, test runner sẵn có và env-file/watch không cần framework bổ sung; chưa đánh giá tải lớn |
| Express 5.3.0 trong lockfile | Router/middleware/JSON parser đủ cho sáu endpoint; validation và nghiệp vụ vẫn viết rõ theo hợp đồng, không coi parser là validator |
| better-sqlite3 12.11.1 + SQLite | DB file dễ tạo lại cho bài tập; callback đồng bộ giúp task/receipt cùng transaction. Native module cần đúng Node/máy, không chép node_modules qua máy khác |
| WAL, foreign key, synchronous FULL | Đọc/ghi và quan hệ receipt được cấu hình trong connection; không suy ra an toàn trước mọi lỗi ổ đĩa/mất điện khi chưa thử |
| yaml 2.9.1, dev dependency | Đọc contract cho test; evaluator subset riêng, chưa dùng validator OpenAPI đầy đủ |

Phiên bản cài thật, nền tảng và lệnh của lượt bàn giao nằm ở [test-results](../testing/test-results.md). Các lý do trên là lựa chọn thiết kế, không phải kết luận so sánh hiệu năng với framework/DB khác.

## 4. API và dữ liệu (D2, D4)

[OpenAPI](../../contracts/openapi.yaml) mô tả sáu endpoint `/api/v1`: health, list, detail, POST, PUT, DELETE. [JSON mẫu](../../contracts/examples/README.md) cho request/envelope task/list và lỗi. UUID giữ ổn định, enum cố định, dueDate ngày thực hoặc null, boolean đúng kiểu; title trim 1–120 code point, description tối đa 2.000. Không normalize Unicode hoặc dùng dữ liệu thời gian client làm thẩm quyền.

GET list thiếu includeDeleted hoặc true trả cả tombstone; false chỉ trả task sống; query sai/lặp 422. POST tạo version 1/201, UUID đã tồn tại kể cả tombstone trả 409/currentTask. PUT toàn bộ trường và baseVersion, không upsert; version khớp mới tăng và trả 200. DELETE trả 200/envelope tombstone; matched tombstone với mutation mới giữ toàn bộ task/version/time nhưng lưu receipt mới; stale vẫn 409. 404/422/MUTATION_ID_REUSED có schema riêng, 409 version luôn có currentTask đầy đủ.

Ba bảng `tasks`, `mutation_receipts`, `schema_migrations` và snake_case ↔ camelCase được giải thích trong [database-design](../database-design.md). Migration đã áp dụng có SHA-256 checksum, sửa schema bằng migration mới. Seed ba task cố định chỉ chèn khi tasks/receipts rỗng; nếu đủ seed ID thì bỏ qua, không ghi đè; partial/mixed dataset thiếu seed ID bị từ chối. Reset chỉ CLI có xác nhận và environment guard; không có API reset và không dùng reset để làm test pass.

## 5. Version, receipt, xóa logic và lựa chọn xung đột (D5)

Luồng và sơ đồ phản ánh mã thật ở [architecture](../architecture.md). Nhận diện tối thiểu/fingerprint trước; trong cùng transaction đọc receipt trước full validation/version. Cùng key/request replay status/body đã lưu; khác request 409 MUTATION_ID_REUSED. Mutation mới hợp lệ ghi task, kiểm response rồi ghi receipt; COMMIT thành công mới trả response. Validation/conflict/lỗi SQL/COMMIT không để lại success receipt. POST cũ có thể replay version 1 dù task hiện là version 4.

Giữ tombstone giúp thiết bị offline biết task đã bị xóa; vắng mặt trong list không phải xác nhận xóa. Giữ receipt giúp retry sau restart/mất response không áp dụng lại; chưa có garbage collection/retention policy. DELETE no-op cũng có receipt nên sau khi task được phục hồi, retry DELETE đó chỉ trả tombstone cũ, không xóa lại bản hiện tại.

Khi 409 thực sự, client giữ snapshot thiết bị và currentTask để người dùng chọn **toàn bộ một phiên bản**, chưa merge từng trường. Giữ server nghĩa là nhận bản server; giữ thiết bị phải gửi mutation mới dựa trên version server hiện hành, và có thể lại conflict nếu server đổi tiếp. Chọn một bản có thể bỏ mất thay đổi chỉ có ở bản kia; người dùng cần xem đủ trường trước chọn. Cùng nội dung hoặc chỉ một phía sửa cần kiểm hội tụ, không bắt chọn vô ích. Backend cung cấp snapshot/precondition, nhưng UI/resolver/recheck/Room thuộc A và chưa được kiểm trên hai thiết bị.

## 6. Kiểm thử và thực nghiệm (D6, D7)

Nguồn chạy thật ở [test-results](../testing/test-results.md), ánh xạ quy tắc/test ở [test-cases](../testing/test-cases.md). Mốc Bước 9 có 147 test; Bước 10/11 là 155, smoke 11 request và năm fault scenario. Bước 13 ghi lượt tái hiện riêng từ bản sao có kiểm soát gồm tài liệu chưa commit, không lấy kết quả cũ làm lượt chạy mới. HTTP qua app thật + SQLite, rollback lỗi thực, restart hai process và Worker tranh khóa kiểm các rủi ro khác nhau.

Ba race service dùng hai Worker/connection giữ gate, nhận BUSY rồi retry thật. Restart hai process là tuần tự, không gọi là concurrency. Fault timeout-before-commit giữ trước dispatch; lost-response phá downstream sau commit được receipt xác minh. Không tuyên bố đã kiểm mọi lỗi mạng, stress HTTP hoặc mất điện giữa COMMIT. OpenAPI kiểm YAML/local refs/examples/schema subset và HTTP thực, chưa có validator chuyên dụng.

[measurements.csv](../testing/measurements.csv) hiện chỉ có header: **chưa đo D7**. Duration chẩn đoán của test/log không phải dataset benchmark; số test pass không phải tỷ lệ hội tụ/độ trễ sync. Chưa thể kết luận nhanh/chậm, tiết kiệm băng thông hay ưu việt về hiệu năng. B cần thống nhất dataset, chỉ số, thiết bị/mạng, số lần lặp và nguồn raw data với cả nhóm trong nhiệm vụ riêng.

## 7. Đối chiếu D1–D8 và phân công

Bảng theo phần 17 của [đặc tả dự án đã có](../../StudyFlow_Dac_ta_va_ke_hoach_du_an.docx) và yêu cầu người dùng; cần đối chiếu đề PDF gốc khi nộp. “Có mã/tài liệu” và “đã chạy” được tách rõ.

| Đầu ra | Đã có / mức bằng chứng | Thiếu / người tiếp tục |
|---|---|---|
| D1 Lý thuyết có nguồn | Phần 2–5 trên, [references](../references.md): backend/version/transaction/replay; tài liệu lý thuyết | A bổ sung MVVM/Room/Retrofit/Flow/WorkManager theo phiên bản; cả nhóm tổng hợp báo cáo có nguồn, chưa có báo cáo cuối |
| D2 Yêu cầu, use case, sơ đồ, DB/UI | [requirements](../requirements.md), [architecture](../architecture.md), [database](../database-design.md), [UI spec](../design/ui-spec.md), hợp đồng; backend có mã/test | A xác nhận sơ đồ/use case Android và luồng sync thực; sơ đồ backend không đại diện đường A/B đã chạy |
| D3 Kotlin và hướng dẫn Android | [Mã/README Android](../../android/README.md), Wrapper/schema đã có; lịch sử kết quả do A ghi | A xác nhận build/install hiện tại, hoàn thiện mạng/scheduler/resolver; Bước 13 không build/cài Android |
| D4 Server/API docs/seed/deploy | [Mã và README server](../../server/README.md), lockfile, migration/seed, contracts, [setup](../setup.md); lượt sạch Bước 13 trong test-results | Đủ bàn giao backend chạy local theo môi trường đã thử; chưa deploy public/HTTPS, không có release đã xuất bản |
| D5 Đồng bộ/thuật toán/retry/conflict | Executor/service/test, sơ đồ backend, [demo](../demo/demo-script.md); backend retry/conflict đã chạy thật | A/cả nhóm chứng minh outbox/SyncEngine, full-version choice và hội tụ trên app thật |
| D6 Test/pass-fail/log/ảnh | Test source, ma trận BE/IT, kết quả backend; fault tool in log/assertion thật khi chạy, không commit raw log | IT Android/hai thiết bị chưa chạy; cần A/B thu ảnh/log/Room thật theo ma trận. Không biến ảnh fake thành server proof |
| D7 Môi trường/số đo/phân tích | Môi trường và giới hạn trong test-results; CSV header hiện có | B tổng hợp cùng nhóm: chưa có dataset đo, raw data, bảng số đo hoặc phân tích thực nghiệm; không điền 0 cho chưa đo |
| D8 APK/video/README tái hiện | README/setup/demo backend đã có, kiểm kê ở [releases](../../releases/README.md) | A cung cấp APK đã cài/thử; A/B quay video offline/server/hai thiết bị sau tích hợp. Chưa có artifact cuối được xác minh |

## 8. Giới hạn và việc còn lại

Backend đủ cho demo/local API độc lập trên môi trường đã thử, không chứng minh toàn dự án hoàn tất. Chưa có mạng Android, hội tụ hai storage, benchmark, validator OpenAPI đầy đủ, stress hoặc kiểm mất điện. Không có auth/HTTPS/public hosting, incremental sync, realtime, merge từng trường, lịch sử khôi phục, dọn receipt/tombstone. Hoàn thiện tích hợp A/B và D7/D8 cần nhiệm vụ/bằng chứng riêng; không tự triển khai chúng trong Bước 13.
