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

## Bước 11 — bàn giao đã chuẩn bị, ghép Android chưa chạy (30/10/2026)

Lượt này bắt đầu ở `da465da`, nhánh `codex/backend-api-contract`, working tree sạch. Chỉ cập nhật tài liệu hiện có; không đổi code/config Android, backend production, dependency hoặc hợp đồng. [Setup](../setup.md) đã thay nội dung scaffold cũ bằng lệnh thực tế và danh sách bàn giao cho A.

### Môi trường và đường chạy đã kiểm tra

| Nội dung | Quan sát thực tế |
|---|---|
| Backend | Node v22.14.0, npm 11.6.4; Express 5.3.0, better-sqlite3 12.11.1 theo dependency/lock hiện có |
| Profile có trước | `.env` đang trỏ HOST=0.0.0.0/PORT=3000/DATABASE_PATH=./storage/studyflow.db; DB có trên máy, không mở ghi/reset/seed trong lượt này |
| Listener | Lúc kiểm kê không có listener 3000/3001; không dừng server của người dùng |
| Java/SDK/Wrapper | JDK mặc định Temurin 21.0.4; Android Studio JBR 25.0.3; `./gradlew --offline --no-daemon -Dorg.gradle.java.installations.auto-download=false --version` với JAVA_HOME JBR trả Gradle 9.6.0, criteria JetBrains Java 25; SDK Platform 37.0, Build Tools 36.0.0 có sẵn |
| Thiết bị | ADB devices sau khi có quyền localhost trả danh sách rỗng. Emulator `-list-avds` có `Small_Phone`, chưa chạy; không có hai app storage được xác minh độc lập |
| Android runtime | Không build/cài/chạy app hoặc instrumentation trong lượt này. Chỉ kiểm kê toolchain và đọc mã; không lấy số 82 unit/23 Room mốc cũ làm kết quả mới |

ADB và Wrapper ban đầu bị sandbox chặn kết nối localhost/ghi lock cache. Đã dùng cơ chế cấp quyền và chạy lại đúng lệnh: ADB thực sự không có device, Wrapper version exit 0. Không tải JDK/Gradle/SDK/emulator, không sửa local.properties, không cài đè hoặc pm clear app.

### Kiểm tra backend độc lập đã chạy

1. `cd server && npm test -- --test-reporter=tap`: **155 tests, 155 pass, 0 fail/skipped/cancelled/todo, exit 0**. Gồm contract subset/fixture, concurrency Worker, persistence/replay và năm scenario runner Bước 10; không phải Android.
2. `cd server && npm run test:smoke`: **11/11 request đạt, exit 0**. Tiến trình `src/server.js` riêng, port động/DB tạm, GET/health/validation/tombstone; DB không đổi và được dọn.
3. Tái sử dụng chuỗi kiểm tra cURL Bước 8 dưới dạng công cụ tạm ngoài repo, đổi sang HOST=0.0.0.0, port 3001 đang trống, DB mới chỉ migrate và UUID task `00000000-0000-4000-8000-000000011001`. **15 request HTTP thật đạt** qua `src/server.js`:

| Thao tác | Kết quả quan sát |
|---|---|
| Host GET health | 200, body rỗng |
| POST và GET | 201/200, cùng UUID, version 1 |
| PUT rồi PUT version cũ bằng mutation mới | 200/version 2; 409 VERSION_CONFLICT có currentTask đầy đủ |
| DELETE baseVersion 2 rồi GET | 200/tombstone version 3, nội dung giữ nguyên |
| Retry POST/DELETE cũ | 201/version 1 và 200/tombstone version 3 từ receipt |
| DELETE key mới/baseVersion 3 trên tombstone | 200, giữ version/timestamp/nội dung, receipt mới |
| PUT phục hồi baseVersion 3 | 200/task sống version 4 |
| Retry DELETE no-op cũ sau phục hồi | 200/snapshot tombstone version 3, không xóa lại task hiện tại |
| GET detail/list includeDeleted=true; DELETE mới version cũ | GET giữ task sống version 4; DELETE 409/currentTask version 4 |

Các thao tác trên là **HTTP client trên host**, không phải thao tác người dùng trên Android. Không gắn nhãn A/B cho hai lần cURL. Đọc SQLite test read-only sau khi dừng server: một task sống version 4, năm receipt, integrity_check=ok. Server do công cụ tạo dừng bình thường exit 0, thư mục DB tạm đã xóa. Hash `.env`, DB demo và WAL có trước giữ nguyên; không đưa log/evidence/DB runtime vào Git. Dòng log request có cấu trúc đã được quan sát, nhưng receipt/response/state mới là assertion chính.

### Audit Android và các điều kiện chưa đạt

| Thành phần / bằng chứng đọc mã | Kết luận |
|---|---|
| `StudyFlowApplication.kt` chỉ tạo RoomTaskRepository | Chưa bind TaskApi/RoomSyncStore/SyncEngine vào app chính |
| `sync/api/TaskApi.kt` là suspend interface/DTO; Gradle không có Retrofit/OkHttp | Chưa có client HTTP thật hoặc cấu hình base URL tập trung |
| Fake ở `src/debug/.../StatefulFakeTaskApi.kt` và các test | Fake không phải network; không được gọi từ production Application/nút sync |
| `SyncScreen.kt` Button enabled=false/onClick rỗng | Đồng bộ UI chưa chạy thật, không có kết quả manual sync A/B |
| Scheduler/Worker/ConflictResolver là TODO; thiếu WorkManager dependency | Chưa có auto scheduling hoặc xác nhận lựa chọn conflict thật |
| Main/debug manifest chưa có INTERNET/debug cleartext | Chưa cấu hình để app gọi HTTP local; không tự thêm quyền vào release trong nhiệm vụ docs này |
| `validAcknowledgement()` luôn đòi baseVersion+1 | Chênh lệch với DELETE no-op matched đã chốt: server giữ version. Cần A sửa/test trước ghép; đây là kết luận từ mã, chưa phải failure trên thiết bị |
| `TaskSnapshot.isValid()` dùng UTF-16 String.length | Chưa khớp code point backend ở biên emoji/title/description. Cần regression/đối chiếu Unicode phía A, không đổi hợp đồng |

Các ca IT-A…IT-L về A → server → B, B không sửa, hai task khác nhau, offline/tombstone, chọn phiên bản, stale confirmation, hội tụ cùng nội dung, mất response của app, sửa trong lúc gửi và hội tụ Room **đều chưa chạy**. Lý do: không có adapter/binding thực và không có A/B. IT-02 health từ thiết bị cũng chưa chạy; host 200 không được nâng thành device/app 200.

Không có truy vấn Room A/B, ảnh UI hoặc bằng chứng outbox thực từ thiết bị trong lượt này. Không suy ra Room giống server từ số lượng, ảnh hoặc fake test. Trạng thái ban đầu pending/conflict của A/B chưa thể đọc khi không có device. Ma trận expected/actual và yêu cầu bằng chứng nằm ở [test-cases](test-cases.md#bước-11-bàn-giao-và-ma-trận-ghép-hai-thiết-bị).

### Bàn giao và giới hạn

Đã chuẩn bị [lệnh server riêng/network/cleanup và nội dung cho A](../setup.md), liên kết OpenAPI/JSON, nhắc includeDeleted=true, DELETE no-op, receipt trước version và snapshot retry. Không nhắn sang chat khác/email/dịch vụ. Không tự viết thêm Android network stack, UI, WorkManager hoặc resolver vì đó là các thành phần còn thiếu của A.

Bước 11 hoàn thành phần backend và chuẩn bị bàn giao có thể làm độc lập; **chưa hoàn thành ghép API thật với Android hoặc kiểm thử hai thiết bị**. Không benchmark, không chuyển Bước 12, không commit/push/merge/reset. Khi A có adapter/binding và hai storage test được phép, mới mở lại các ca tích hợp trong ma trận; không dùng công cụ Bước 10 trên server chung để thay bằng chứng của app.

## Bước 13 — tái hiện và bàn giao backend (30/10/2026)

### Nguồn mã và khả năng tái hiện

Nhánh `codex/backend-api-contract`, HEAD nền `da465dafb84026cf6541177f89a65b5bf183568d`. Bước 9 đã nằm trong commit `c06732e`, Bước 10 trong `da465da`. Trước Bước 13 có năm Markdown Bước 11 chưa commit (server README, setup, demo, test-cases, test-results); đã sao lưu riêng trước chỉnh sửa và giữ nội dung/bằng chứng của chúng. Không giả định Bước 11 tích hợp hoặc Bước 12 đã hoàn tất: ma trận IT chưa chạy, measurements.csv chỉ header.

Lượt này tạo **bản sao có kiểm soát** bằng archive HEAD rồi overlay các Markdown đang sửa, trong thư mục tạm ngoài repo, không có `.git`, node_modules, `.env` hay DB runtime ban đầu. Bao gồm thay đổi chưa commit của Bước 11/13, không gọi đây là tái hiện chỉ từ remote commit hiện tại. Các chỉnh sửa Markdown cuối để ghi kết quả được đồng bộ vào bản sao để kiểm link; không thay source/test/contract/lock trong repo. Hash **81 file** source, test, tooling, package/lock, `.env.example` và contracts trùng với bản sao sau thử; migration checksum cũng được phục hồi đúng sau probe có chủ ý.

| Môi trường lượt sạch | Quan sát |
|---|---|
| Nền tảng | macOS / darwin arm64 |
| Runtime | Node v22.14.0, npm 11.6.4 |
| `npm ls --depth=0` | Express 5.3.0, better-sqlite3 12.11.1, yaml 2.9.1 |
| SQLite từ native driver cài mới | 3.53.2; nạp/chạy truy vấn được |
| Profile riêng | NODE_ENV=test, HOST=127.0.0.1, PORT=49209 được cấp khi trống, DATABASE_PATH tương đối dưới storage của **bản sao**; không sửa profile máy làm việc |
| Database mới | Migrate 001_initial.sql; seed 3 task (1 tombstone), 0 receipt; inspect integrity=ok |
| Chạy lại migrate/seed | Không thêm migration/task/receipt hoặc đổi dữ liệu; checksum `c2e9d6aa836be0cf3c08fcf5ef513c929e28eb942e17971aace9891f69e3d645` |

Các lệnh thực hiện trong `server/` của bản sao, theo README (seed ở đây được chọn để kiểm dữ liệu mẫu):

```bash
npm ci --no-audit --no-fund --fetch-retries=0 --fetch-timeout=20000
if [ ! -f .env ]; then cp .env.example .env; fi
# Profile của bản sao được chỉnh sang loopback/cổng trống/DB mới như bảng trên.
npm run db:migrate
npm run db:seed
npm run db:inspect
npm run db:migrate
npm run db:seed
npm start
# Sau khi dừng đúng server của lượt này:
npm test -- --test-reporter=tap
npm run test:smoke
npm run test:faults
npm ls --depth=0
```

`npm ci` thực sự cài mới **101 package**, không mang node_modules từ workspace sang, exit 0. Có warning upstream `prebuild-install@7.1.3` deprecated; không đổi dependency/lockfile để xóa warning. Guard `.env` được thử cả khi thiếu (chép đúng mẫu) và khi đã có (giữ nguyên bytes). Profile chạy HTTP là cấu hình riêng từ các biến mẫu, không phải cấu hình cá nhân/DB demo.

### Kết quả đã chạy mới

| Kiểm tra | Kết quả |
|---|---|
| Toàn bộ test backend | **155/155 pass**, fail/cancelled/skipped/todo 0, exit 0 |
| Smoke entrypoint thật | **11/11 HTTP request đạt**, DB không đổi; child riêng dừng, exit 0 |
| CLI lỗi Bước 10 | **5/5 scenario pass**, exit 0; `childrenStopped`, `proxyClosed`, `connectionsClosed`, `directoryRemoved` đều true |
| HTTP trên `npm start` với DB vừa seed | **25 request có assertion đạt**, đủ sáu endpoint; JSON có Content-Type UTF-8, health body rỗng |
| Đối chiếu HTTP với OpenAPI hiện có | **25/25 response khớp evaluator subset**, 51 local refs, 12 external example occurrence; toàn bộ 13 JSON fixture được kiểm trong suite |
| Guard/vận hành | Sáu ca từ chối đúng: thiếu env, PORT=0, DB chưa có, schema trống, cổng do checker giữ, migration đã chạy bị sửa |
| Dừng server | Gửi SIGINT cho process group chỉ thuộc lượt thử; npm wrapper exit -2 (bị ngắt như Ctrl+C), listener đóng; không coi wrapper bị ngắt là exit 0 |

Không có validator OpenAPI chuyên dụng trong dependency hiện có; `yaml` là parser, helper kiểm một tập keyword/format. Không cài thêm validator/framework hoặc gọi kiểm tra subset là chứng nhận toàn bộ OpenAPI/JSON Schema/RFC. Ba race dùng hai Worker/SQLite connection tranh khóa thật rồi retry vẫn nằm trong 155 test; restart hai process là kiểm persistence tuần tự, không gọi là đồng thời. Không đo benchmark hoặc stress.

Task HTTP riêng: `00000000-0000-4000-8000-000000013001`, không trùng seed. Các mutation key đuôi 013010…013018 giúp theo dõi response/receipt:

| Nhóm request thực | Quan sát |
|---|---|
| Health, list thiếu query/true/false | Health 200 rỗng; list mặc định bằng true có 3 task, false có 2 task sống |
| Query sai/lặp, ID sai/chưa tồn tại | 422 fieldErrors hoặc 404 TASK_NOT_FOUND đúng; không có success receipt |
| POST mới, GET, retry POST | 201/version 1, GET cùng task; retry trả snapshot cũ |
| Cùng key đổi request / POST key mới trùng UUID | 409 MUTATION_ID_REUSED / 409 VERSION_CONFLICT với currentTask đầy đủ |
| POST boolean dạng chuỗi | 422 VALIDATION_ERROR, không tạo task/receipt |
| PUT mới và PUT stale | 200/version 2, sau đó 409/currentTask version 2 |
| DELETE sống, GET tombstone, DELETE key mới matched và retry | 200/tombstone version 3; no-op giữ toàn bộ task/time/version, receipt mới; retry không thêm receipt hoặc đổi DB |
| DELETE tombstone stale | 409/currentTask đầy đủ version 3 |
| PUT phục hồi, retry no-op DELETE và POST cũ, GET | Sống version 4; receipt trả tombstone version 3 hoặc POST version 1 như cũ; task hiện tại không bị xóa/ghi đè |

Cuối chuỗi: **4 task = 3 seed + 1 task sống version 4; 5 success receipt; integrity=ok**. So toàn bộ hàng task/receipt/migration trước và sau các replay, không chỉ đếm version hoặc tin log. Inspect cuối đọc thật từ DB bản sao. CLI fault cuối `lost-response` cũng quan sát replay snapshot v1 trong khi currentTask v2, receipt count không đổi.

Các lỗi khởi động đều thử ở bản sao/DB/cổng do checker sở hữu. Checksum probe chỉ thêm comment tạm vào migration của bản sao, lệnh migrate bị từ chối; file được trả lại đúng bytes và state DB không đổi. Không dùng/reset/sửa DB demo, không đổi source production để làm test đạt.

### Lỗi môi trường/công cụ và giới hạn bằng chứng

Lần npm ci sandbox đầu báo `Exit handler never called` và không ghi được cache npm; đã cấp quyền rồi chạy lại hoàn tất. Lần mở socket đầu bị EPERM; đã cấp quyền localhost và chạy thật, không skip. Checker tạm ban đầu gặp lỗi đọc bằng SQLite của Python; lượt cuối đọc read-only bằng đúng better-sqlite3 của dự án. Checker còn sửa giả định sai rằng npm wrapper sau SIGINT luôn exit 0 và chuyển probe cổng bận sang socket mới do nó sở hữu, tránh TIME_WAIT của cổng vừa đóng. Các lượt checker chưa hoàn tất không được ghi thành pass; lượt cuối hoàn tất cả HTTP, vận hành và cleanup, không sửa production.

Checker, log, config, node_modules và DB của bản sao là tài nguyên tạm ngoài repo, không là artifact nộp. Lệnh tái hiện được lưu cho người nhận là README và các test/tool trong Git; không yêu cầu chạy lại script `/private/tmp` của phiên làm việc. Hash `.env` cùng DB/WAL/SHM có trước ở workspace được so lại sau lượt thử; không thay đổi. Không chạm Android hoặc tạo Word/PDF/báo cáo cuối mới, không stage/commit/push/merge/reset.

Backend đã được kiểm độc lập trên môi trường trên và có đủ hướng dẫn để bàn giao local. Chưa có xác nhận Windows/Linux/public deploy/HTTPS, validator đầy đủ, mất điện/crash trong COMMIT, Android thật/hai thiết bị, D7 hoặc APK/video cuối. Bảng [D1–D8](../report/outline.md#7-đối-chiếu-d1d8-và-phân-công) phân rõ phần A/B/cả nhóm. Các kết quả backend không được nâng thành toàn dự án hoàn tất.

Kiểm tra tài liệu cuối: **16 Markdown thay đổi, 156 liên kết tương đối/anchor được kiểm, không có link hỏng**; các command npm đối chiếu package scripts. Swagger CLI/Redocly/Spectral/OpenAPI generator không có trong PATH; dependency hiện có không cung cấp validator chuyên dụng. Các file nguồn/lock/migration/seed/contract/test cần thiết được Git theo dõi. `git diff --check` đạt, index không có file staged, không thêm file runtime/secret/dependency hoặc sửa Android. Bản sao cài mới và tài nguyên runtime riêng được dọn sau kiểm tra; tài liệu Bước 11 có trước được giữ và cập nhật trong cùng phạm vi Markdown.

## Đối chiếu TC01–TC09 trước commit Bước 12/13 (31/10/2026)

Không tìm thấy bộ dữ liệu thực nghiệm Bước 12 trong thay đổi hoặc lịch sử hiện có; `measurements.csv` vẫn chỉ có header, không có raw data để tổng hợp. Không tạo số liệu, thống kê, tỷ lệ thành công hoặc kết luận hội tụ thay thế. Không chạy benchmark chỉ để tạo commit. Các kết quả Bước 9–11/13 ở trên thuộc backend độc lập hoặc công cụ test; kết quả fake/Room cũ phía Android có phạm vi riêng ở [sync-engine-phase5-results](sync-engine-phase5-results.md).

Bảng dưới đối chiếu tên ca trong [đặc tả dự án đã có](../../StudyFlow_Dac_ta_va_ke_hoach_du_an.docx), không xác nhận đã đọc PDF đề gốc. Trạng thái là **nghiệm thu toàn ca Android/tích hợp**: chưa có bằng chứng chạy đầy đủ TC01–TC09 trong Bước 12, nên không đánh dấu pass hoặc fail toàn ca từ kết quả backend.

| Ca | Mục tiêu | Trạng thái toàn ca / bằng chứng giới hạn hiện có |
|---|---|---|
| TC01 | Online tạo trên A, server nhận, B nhận cùng ID | Chưa chạy tích hợp A/server/B; POST/GET backend đã đạt riêng, không chứng minh app đồng bộ |
| TC02 | Offline tạo/sửa, Room/UI/pending bền sau mở lại | Chưa chạy nghiệm thu Bước 12; test fake/Room lịch sử không được nâng thành tích hợp API thật |
| TC03 | Khôi phục mạng tự gửi, tách độ trễ scheduler | Chưa chạy/chưa đo; scheduler/worker/binding chưa hoàn thiện theo audit Bước 11 |
| TC04 | Đóng/mở app giữ dữ liệu/outbox và tiếp tục tác vụ | Chưa chạy nghiệm thu trên app; restart backend chỉ chứng minh receipt/task server bền, không phải outbox hoặc force stop Android |
| TC05 | Server ngừng/timeout, giữ thay đổi và retry đúng mutation | Chưa chạy đầy đủ từ app; năm fault scenario backend đạt riêng, không chứng minh UI/outbox/WorkManager |
| TC06 | Retry cùng mutation không tạo trùng hoặc tăng version lại | Chưa chạy từ app thật; backend đã kiểm receipt/replay/version và một lần áp dụng, retry không được đếm thành mutation mới |
| TC07 | A/B sửa cùng task, chọn bản và hội tụ | Chưa chạy hai thiết bị/resolver; 409/currentTask backend đạt riêng, không chứng minh lựa chọn hoặc hội tụ Room |
| TC08 | Xóa offline truyền tombstone sang B, không xuất hiện lại | Chưa chạy offline A/B; backend đã kiểm tombstone/no-op/restore/replay theo hợp đồng |
| TC09 | Nhiều bản ghi: thời gian, thành công, trùng và không nhất quán | Chưa chạy/chưa đo; không có dataset/kích thước/lần lặp/đơn vị/raw data thực nghiệm, không điền 0 |

Chưa có số đo API/SyncEngine/chờ WorkManager để cộng hoặc so sánh; duration chẩn đoán trong log không thay dữ liệu thực nghiệm. Những khẳng định version/không áp dụng trùng ở đây chỉ dựa trên assertion SQLite/HTTP của backend, chưa là bằng chứng hội tụ toàn hệ thống. D7, tích hợp API thật hai thiết bị và artifact APK/video/báo cáo cuối vẫn còn thiếu như bảng D1–D8. Commit tài liệu lưu đúng trạng thái này, không phải commit kết quả thực nghiệm Bước 12 đã hoàn tất.

Kiểm tra trước commit ngày 31/10/2026: `cd server` rồi `node --test tests/openapi.test.js` chạy mới **5/5 đạt**, fail/skipped/cancelled 0, gồm YAML/refs/13 JSON mẫu và HTTP production với DB tạm. Kiểm tra **159 liên kết tương đối/anchor**, syntax **52 block shell** bằng `bash -n`, parse **15 JSON** (package/lock và 13 fixture) đều đạt; npm scripts được đối chiếu package.json, CSV đúng header và chưa có dữ liệu đo. `.env` cùng DB/WAL/SHM có trước giữ nguyên hash. Không có thay đổi production/test/dependency/contract/Android, nên không chạy lại toàn bộ 155 test hoặc benchmark trong lượt commit này; số 155/11/5/25 ở phần Bước 13 vẫn là bằng chứng ngày 30/10/2026.
