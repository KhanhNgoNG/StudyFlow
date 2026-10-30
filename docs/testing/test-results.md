# Kết quả kiểm thử

## Backend Bước 9 — đã chạy ngày 30/10/2026

Nhánh làm việc `codex/backend-api-contract`, bắt đầu từ `19acb9c` (Bước 8 đã commit). Node **v22.14.0**, npm **11.6.4**, Express **5.3.0**, better-sqlite3 **12.11.1**; `yaml@2.9.1` là dependency chỉ dành cho test. Giữ `node:test`, không thêm framework hoặc thay dependency runtime. Xem [bảng audit trước bổ sung](test-cases.md#audit-backend-trước-bước-9) và [README server](../../server/README.md).

Các kết quả dưới đây đã chạy thật trên backend độc lập. Không dùng, reset, seed lại hoặc sửa database demo; các bài test có fixture, migration và database riêng. Không chạy app Android hoặc hai thiết bị trong lượt này.

## Kết quả tự động

Đã chạy lại bộ Bước 4–8 trước khi thêm test: **136 pass, 0 fail, 0 skipped, 0 cancelled**. Sau bổ sung, chạy từ `server/`:

```bash
cd /Users/tuankhong/Documents/StudyFlow/server
npm test -- --test-reporter=tap
```

Kết quả cuối: **147 tests, 147 pass, 0 fail, 0 skipped, 0 cancelled, 0 todo; exit code 0**. Lệnh thường ngày `npm test` chạy cùng toàn bộ test, gồm OpenAPI; không cần chạy script Python ngoài repo. Máy mới cần `npm ci` với Node đúng engines trước.

| File test | Số đạt | Mục đích và tầng kiểm tra |
|---|---:|---|
| `database.test.js` | 12 | Schema/migration/seed/transaction và persistence, SQLite thật và child process đọc |
| `validation.test.js` | 30 | Quy tắc trường/kiểu/Unicode/ngày/version/query, unit |
| `responses.test.js` | 4 | Mapper/envelope/error fixture, gồm SQLite thật |
| `http.test.js` | 10 | HTTP adapter validation/parser/error, không phải toàn bộ production write path |
| `task-repository.test.js` | 8 | Đọc SQLite, không ghi dữ liệu và snapshot WAL hai connection |
| `read-api.test.js` | 16 | API GET/health trên production app |
| `request-fingerprint.test.js` | 12 | Canonicalization/hash raw JSON, unit |
| `mutation-executor.test.js` | 21 | Receipt/replay/rollback/persistence, gồm hai Worker tranh khóa |
| `mutation-http.test.js` | 3 | Executor qua app HTTP test riêng, mất response và rollback |
| `task-write-api.test.js` | 18 | Production CRUD/version/tombstone/replay/fault trước commit và mất response sau commit |
| `task-write-concurrency.test.js` | 3 | Production service, hai Worker/hai connection tranh write lock thật |
| `write-http-contract.test.js` | 4 | Production HTTP biên validation/Unicode, key order/kiểu, receipt hỏng |
| `restart-replay.test.js` | 1 | Production HTTP qua hai tiến trình mới, file SQLite tạm và bảy receipt replay |
| `openapi.test.js` | 5 | YAML/ref/parameter/fixture/subset schema, request và response HTTP thực tế |
| **Tổng** | **147** | **0 fail, skip hoặc cancel** |

Đã chạy riêng các nhóm mới khi triển khai: write HTTP **4/4**, restart **1/1**, OpenAPI **5/5**, concurrency **3/3**. File concurrency còn được chạy thêm **5 lượt độc lập, mỗi lượt 3/3**, chỉ để kiểm tra độ ổn định của barrier/timeout; không đo tốc độ hoặc coi đây là benchmark/stress.

Một số lượt chạy trực tiếp `node --test` ban đầu bị sandbox chặn listen localhost với **EPERM**. Sau khi cấp quyền mở port động đã chạy lại đạt; không skip, giảm assertion hoặc sửa production để che lỗi môi trường. Kết quả 147/147 ở trên là lượt toàn bộ cuối đã hoàn tất.

## Bằng chứng về hành vi và ranh giới kiểm tra

- **Nguyên tử:** dùng transaction SQLite thật. Lỗi callback/nghiệp vụ, INSERT receipt hoặc deferred FK lúc COMMIT phải rollback. Các assertion HTTP và snapshot DB không cho phép trả thành công một phần. Không mock bỏ qua transaction.
- **Mất response:** test cũ dùng transport wrapper ở ngoài production app, phá kết nối sau POST đã commit; retry cùng key nhận snapshot cũ, không áp dụng lại. Đây khác lỗi trước commit; không xây proxy/công cụ Bước 10.
- **Restart:** process A tạo hai task và bảy receipt, gồm POST/PUT/DELETE/no-op/restore; A dừng bình thường code 0. Process B mở cùng file với app/connection mới và replay bảy request giữ nguyên status/body. Task sống version 4, tombstone version 2, các receipt/migration và integrity_check giữ nguyên. B cũng dừng code 0. Không gọi đây là forced crash hoặc power-loss test.
- **Concurrency:** hai Worker có hai SQLite connection. Writer giữ khóa trong TEMP trigger/gate test; contender thật sự nhận SQLITE_BUSY trước commit. Sau khi commit, retry khác key/cùng version nhận VERSION_CONFLICT; cùng key/request nhận replay; cùng key/khác body nhận MUTATION_ID_REUSED trước conflict version. Chỉ winner được ghi task/receipt, loser không ghi. Đây là contention + retry ở tầng service/connection, không phải HTTP concurrency hoặc stress test.
- **Receipt hỏng:** cố ý sửa JSON/schema/hash/path/time chỉ trong DB test. CHECK enforcement được bật lại trước khi gửi HTTP. Production trả 500 kín, không áp dụng lại mutation, snapshot DB không đổi. Không có endpoint fault/reset mới.
- **OpenAPI:** parser YAML từ chối key trùng; **51 local refs**, **12 external example occurrence**, toàn bộ **13 JSON fixture** được kiểm tra. Parameter bắt buộc/default includeDeleted và request/response HTTP CRUD, 404/409/422, health 200/503 được đối chiếu. DELETE tombstone/no-op/replay sau restore và default true vẫn nhất quán với hợp đồng. Đây là evaluator subset có guard keyword, không phải kết quả từ validator OpenAPI chuyên dụng hoặc toàn bộ RFC 3339. OpenAPI cho phép một số field bổ sung; các assertion envelope/không lộ metadata của API vẫn được giữ riêng.

Đã chạy thêm smoke test tự động hiện có:

```bash
npm run test:smoke
```

**11/11 request đạt, exit code 0** trên tiến trình `src/server.js` thật, database tạm và port loopback động: GET health/default/true/false/chi tiết sống/tombstone 200, task thiếu 404, ID/query sai hoặc lặp 422, URL encoding sai 400. Snapshot database không đổi, integrity_check=ok, đúng tiến trình do script tạo đã dừng và tài nguyên tạm được dọn. Đây là 11 request smoke riêng, không cộng thành 11 test vào tổng 147.

## Lỗi và thay đổi trong lượt này

Không phát hiện lỗi production cần sửa. Không sửa `server/src/`, schema migration, API contract hoặc Android. Bổ sung các khoảng trống sau audit và chỉnh một comment HTTP test cũ để không mô tả nhầm rằng app chính chưa có API ghi.

Trong review helper OpenAPI mới, phát hiện regex ngày có thể nhận newline cuối chuỗi do dấu `$` trong JavaScript. Đã bổ sung điều kiện độ dài chính xác 10 và regression `2028-02-29\n`; nhóm 5/5 và toàn bộ 147/147 sau sửa đạt. Đây là sửa lỗi hạ tầng test mới, không phải thay đổi validation production vốn đã từ chối trường hợp đó.

## Đã thiết kế, đã chạy và chưa thực hiện

| Phần | Trạng thái |
|---|---|
| Bảng audit và các ca backend BE-01…BE-15 | Đã thiết kế; các test tự động tương ứng đã chạy, kết quả/giới hạn như trên |
| Thao tác thủ công mới bằng cURL hoặc UI trong Bước 9 | Không thực hiện; smoke và HTTP trong lượt này là tự động. Bằng chứng cURL cũ thuộc Bước 6/8, không tính là chạy mới |
| Forced crash/power loss ngay trong COMMIT, stress HTTP, benchmark | Chưa thực hiện; ngoài phạm vi Bước 9 |
| Validator OpenAPI chuyên dụng/toàn bộ JSON Schema và RFC | Chưa thực hiện; chỉ có kiểm tra subset được ghi rõ |
| Room, outbox/SyncEngine, WorkManager, UI xung đột/hội tụ hai thiết bị Android | Chưa được xác minh trong nhiệm vụ backend độc lập này |

Kết quả backend pass không chứng minh toàn bộ ứng dụng Android đã hoàn thành. Không tự triển khai Bước 10, commit, push hoặc thay đổi lịch sử Git.
