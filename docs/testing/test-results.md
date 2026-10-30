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

Kết quả backend pass không chứng minh toàn bộ ứng dụng Android đã hoàn thành. Mốc Bước 9 kết thúc ở bộ test trên, không commit/push hoặc thay đổi lịch sử Git. Phần dưới là Bước 10 được triển khai theo yêu cầu tiếp theo.

## Backend Bước 10 — đã chạy ngày 30/10/2026

Giữ nguyên thay đổi Bước 9 chưa commit và vẫn ở nhánh `codex/backend-api-contract`, HEAD `19acb9c`. Không thêm framework/dependency. Production chỉ thêm logging/correlation và bảo vệ lỗi sink; không sửa executor, service, schema, hợp đồng, Android hoặc database demo. Bộ Bước 9 được chạy lại cùng các test mới, không giảm assertion hoặc skip.

### Các lệnh và kết quả thực tế

```bash
cd /Users/tuankhong/Documents/StudyFlow/server
npm run test:faults
npm test -- --test-reporter=tap
npm run test:smoke
```

| Kiểm tra | Kết quả cuối đã chạy |
|---|---|
| CLI `test:faults` | **5/5 scenario pass**, exit 0; childrenStopped, proxyClosed, connectionsClosed, directoryRemoved đều true |
| Nhóm mới chạy riêng | **8/8 test pass**: 5 request logging, 3 harness; fail/skip/cancel 0 |
| Toàn bộ `npm test` | **155 tests, 155 pass, 0 fail, 0 skipped, 0 cancelled, 0 todo**, exit 0 |
| Smoke `src/server.js` thật | **11/11 request pass**, exit 0; DB tạm không đổi, integrity_check=ok, child riêng đã dừng |
| CLI `--help` / scenario sai | Exit 0 / exit 1; argument sai được từ chối trước khi cấp phát tài nguyên |
| Hai lệnh riêng `lost-response`, `timeout-after-commit` | Mỗi lệnh chạy thêm một lượt để kiểm tra phối hợp gate/socket; đều pass và cleanup true, không phải benchmark |

Một số lệnh `node`/CLI đầu tiên bị sandbox chặn listen localhost hoặc child không sẵn sàng vì EPERM. Đã cấp quyền và chạy lại thật; kết quả ở bảng là các lượt hoàn tất thành công, không coi lỗi môi trường là ca pass và không che bằng skip. `npm start`/`npm run dev` giữ lệnh cũ; test kiểm tra chúng và production không import fault harness. HTTP production nhận header/query có tên fault vẫn xử lý bình thường.

### Bằng chứng từ lượt CLI toàn bộ

Số task/receipt sau đây là **tổng cộng dồn trên DB tạm của lượt chạy cả năm**, không phải database demo. Mỗi scenario dùng task UUID và mutation snapshot riêng; các lệnh chạy riêng bắt đầu từ DB tạm mới.

| Scenario | Client quan sát | Task/receipt tại thời điểm lỗi | Retry đúng mutation |
|---|---|---|---|
| unavailable | ECONNREFUSED, không HTTP status | 1/1, task version 1 đã commit trước khi dừng child | 201/replay, vẫn 1/1 và cùng version/time |
| timeout-before-commit | CLIENT_TIMEOUT, chưa dispatch nên không có request ID production | Vẫn 1/1, chưa có task/receipt cho mutation này | 201/applied, thành 2/2, task mới version 1 |
| transient-before-commit | HTTP 500 INTERNAL_ERROR | Vẫn 2/2; INSERT task và receipt cùng rollback | Sau gỡ trigger: 201/applied, thành 3/3 |
| timeout-after-commit | CLIENT_TIMEOUT; proxy đã nhận đủ 201 upstream | 4/4, task version 1 có receipt | 201/replay, giữ 4/4, version/time nguyên trạng |
| lost-response | ECONNRESET, không nhận success header/body | 5/5, task version 1 có receipt; PUT mới sau đó thành 5/6 và task version 2 | POST cũ 201/replay snapshot version 1; hiện tại vẫn version 2 và 5/6 |

Mỗi scenario xác minh `appliedForMutation=1`, response/status khớp receipt JSON object và toàn bộ state task/receipt không đổi khi replay. Không chỉ dựa vào log outcome. Sau `lost-response`, timestamp snapshot cũ là `2026-10-30T03:51:38.306Z`, task hiện tại sau PUT là `2026-10-30T03:51:38.309Z`; retry giữ cả hai đúng vị trí, không ghi đè task mới. Đây là thời điểm quan sát của lượt test, không phải số đo hiệu năng hoặc quy tắc chọn bản thắng.

Ví dụ correlation lấy từ chính lượt đó:

- Mutation ID POST: `00000000-0000-4000-8000-000000010204`.
- Request ID upstream đã commit nhưng response bị proxy bỏ: `ab7c2e7f-560b-4953-917e-c0c45e33b33d` (client không nhận header này; harness biết từ upstream).
- PUT tiếp theo dùng mutation ID mới `00000000-0000-4000-8000-000000010300` và request ID `1924c02c-d57c-4562-b54a-c31cf9d1a87f`.
- Retry POST giữ mutation ID cũ, request ID mới `9ec73c95-dbcc-40d8-8bd8-d2cf656e8435`, outcome replay, taskChanged=false.

Backend có `request.finished` ở upstream trong khi client phía proxy vẫn nhận ECONNRESET. Điều này minh họa vì sao finish không chứng minh client nhận thành công. Test logging riêng còn phá kết nối ngay ở transport wrapper của production app và xác nhận `request.closed` vẫn giữ committed/applied; không suy ra rollback từ close.

### Ranh giới, lỗi đã sửa và cleanup

- Sửa lỗi thực tế: `logger.error` trước đây gọi trực tiếp, sink ném lỗi có thể làm hỏng response 500 hoặc health 503. Common emitter mới bắt lỗi hàm/getter/rejected Promise. Test vẫn kiểm tra 201, replay, rollback 500 và health 503 khi sink thất bại.
- Log `mutation.completed` sau executor trả về, lúc `database.inTransaction=false` và receipt đã có; các lỗi INSERT/COMMIT thật không có log thành công. DELETE tombstone no-op có outcome accepted_noop và taskChanged=false. Transaction committed của replay chỉ là lookup receipt hoàn tất, không áp dụng nghiệp vụ thêm lần nữa.
- UTC dùng để ghi thời điểm; duration dùng performance.now monotonic. Log chỉ gồm metadata đã chọn, UUID hợp lệ, path/template và stack basename/line; không ghi raw query/header/body/title/description/env/SQL message/full internal path. Input newline/JSON giả trong payload không chèn event log mới.
- Hai timeout được tạo 100 ms sau gate đáng tin cậy. Safety timeout 5 giây dùng CLIENT_DEADLINE khác CLIENT_TIMEOUT, không thể được nhận nhầm là bằng chứng timeout có kiểm soát. Ca drop bắt buộc ECONNRESET; không coi bất kỳ lỗi client nào là “mất response sau commit”.
- Proxy chỉ có mode closure trong tooling. Không có API/reset hoặc header/query bật fault trong production. Trigger chỉ tồn tại trên DB tạm của harness. Không mock transaction SQLite.
- Cleanup test cố ý ném assertion khi proxy **đang giữ response sau commit**, không chỉ sau startup. Sau đó xác minh thư mục tạm đã xóa, PID child không tồn tại và cả port backend/proxy đều ECONNREFUSED. Ca thành công cũng xác nhận bốn cờ cleanup. Toàn bộ có deadline 30 giây cho startup/scenario, timeout từng bước và cleanup hữu hạn.
- Review công cụ đã sửa guard deadline/cleanup proxy từ lúc startup, giữ failure CLI ở metadata/lý do an toàn; không dump raw AssertionError có thể chứa body hoặc đường dẫn.

Không lưu log cá nhân, evidence hoặc database runtime vào Git. Không stage/commit/push/merge/reset. Các thay đổi Bước 9 có trước vẫn được giữ; file chung như README/package/test-results và child helper có phần cập nhật của cả hai bước.

### Giới hạn còn lại

Đã kiểm chứng backend độc lập trên loopback, process riêng và SQLite tạm. Không benchmark/stress, không ép mất điện hoặc crash giữa COMMIT. Timeout-before-commit được tạo trước dispatch, không chứng minh khả năng hủy một transaction SQLite đang chạy. Proxy làm mất response sau khi upstream đã gửi đủ, không mô phỏng mọi lỗi mạng có thể xảy ra. Log là best effort, không thay receipt hoặc bảo đảm delivery.

Không chạy Android/hai thiết bị trong Bước 10; Room, WorkManager, SyncEngine và hội tụ thực tế thuộc phần ghép thử Bước 11. OpenAPI vẫn được kiểm tra subset như Bước 9, không có kết quả mới từ validator chuyên dụng. Không tự triển khai Bước 11.
