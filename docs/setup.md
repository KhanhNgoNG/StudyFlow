# Thiết lập môi trường

## Hiện trạng bàn giao — 30/10/2026

Backend đã có API đọc/ghi, validation, version, tombstone, receipt, log và công cụ lỗi. Android có CRUD Room, outbox và SyncEngine độc lập, nhưng **app chưa gọi API thật**. Fake API chỉ thuộc debug/test, không được gắn vào Application hoặc nút đồng bộ. Vì thiếu client mạng/binding và không có thiết bị đang kết nối, chưa thực hiện được đồng bộ A → server → B. Kết quả host HTTP không được tính là kết quả Android.

| Thành phần kiểm tra thực tế | Hiện trạng |
|---|---|
| Git | Lượt thử 30/10/2026 dùng `codex/backend-api-contract`, HEAD nền `da465da` và tài liệu Bước 11/13 chưa commit lúc đó; khi nhận mã ghi lại commit bằng `git log -1` |
| Backend | Node v22.14.0, npm 11.6.4, Express 5.3.0, better-sqlite3 12.11.1; lockfile và migration có sẵn |
| Cấu hình local có trước | `.env`: HOST=0.0.0.0, PORT=3000, DATABASE_PATH=./storage/studyflow.db; giữ nguyên, không dùng cho lượt thử riêng |
| Java/Gradle | JDK mặc định máy là Temurin 21.0.4; Android Studio có JBR 25.0.3; Wrapper đã kiểm tra offline là Gradle 9.6.0, criteria JetBrains Java 25, mã target JVM 17 |
| SDK | SDK Android trên máy kiểm tra, Platform 37.0, Build Tools 36.0.0, ADB 37.0.1 có sẵn |
| Thiết bị | `adb devices -l` không có thiết bị; có cấu hình AVD `Small_Phone` nhưng không chạy, chưa có hai vùng app storage để ghép thử |
| App | applicationId/namespace `com.studyflow`, minSdk 26, compileSdk 37, targetSdk 36; variant dự kiến local là debug, chưa có variant integration |

Các quan sát Java/SDK/thiết bị ở bảng trên thuộc lượt Bước 11; Bước 13 chạy lại backend độc lập, không kiểm kê lại Android. Đây không phải kết quả build/cài APK hay test hai thiết bị. Không tải SDK/AVD/JDK mới, không mở emulator hoặc cài đè app để tạo kết quả giả. Các lệnh môi trường tạm dưới đây dùng macOS (`/private/tmp`); lệnh Wrapper Windows có trong README Android.

## Backend: cài dependency và giữ cấu hình hiện có

Lệnh cho máy mới, bắt đầu từ thư mục gốc repo. Cách lấy nhánh ở [README gốc](../README.md); hướng dẫn chung migrate → seed tùy nhu cầu → inspect → start ở [README server](../server/README.md). Mỗi terminal mới dưới đây bắt đầu tại gốc repo trước `cd server`; khi đã ở `server/` thì bỏ dòng `cd server`.

```bash
cd server
node --version
npm --version
npm ci
if [ ! -f .env ]; then cp .env.example .env; fi
```

`npm ci` cài theo package-lock, gồm dev dependency để chạy kiểm tra OpenAPI. Dòng cuối chỉ tạo `.env` khi chưa có; không chép đè cấu hình của nhóm. File `.env` và database runtime không đưa vào Git. Không cần chạy lại `npm ci` trên máy hiện tại nếu dependency đã đủ; lượt Bước 11 sử dụng các dependency sẵn có.

## Server tích hợp riêng, không dùng database demo

Terminal A: kiểm tra cổng trước. Nếu `lsof` liệt kê listener ở 3001, không chạy tiếp hoặc dừng tiến trình đó; cần chọn lại cổng có phối hợp. Lượt kiểm tra thực tế dùng cổng 3001 đang trống.

```bash
cd server
lsof -nP -iTCP:3001 -sTCP:LISTEN
export STUDYFLOW_INTEGRATION_DIR="$(mktemp -d /private/tmp/studyflow-integration.XXXXXX)"
export DATABASE_PATH="$STUDYFLOW_INTEGRATION_DIR/studyflow.db"
export NODE_ENV=test HOST=0.0.0.0 PORT=3001
node src/database/cli.js migrate
node src/database/cli.js inspect
node src/server.js
```

`migrate` chạy schema thật trên file mới; `inspect` là đọc. Lượt ghép nên bắt đầu rỗng để nhận diện task từ A/B. Nếu chỉ muốn dữ liệu minh họa trên **file mới này**, có thể chạy `node src/database/cli.js seed` trước khi mở server; không seed lại sau khi A/B đã làm việc. Server không tự migrate hoặc seed. Các biến export ở terminal này được config đọc trực tiếp; lệnh `node src/server.js` không đọc `.env` demo.

Terminal B: host kiểm tra trước, health thành công có body rỗng:

```bash
cd server
curl --max-time 5 -i http://127.0.0.1:3001/api/v1/health
curl --max-time 5 -i 'http://127.0.0.1:3001/api/v1/tasks?includeDeleted=true'
```

Với DB mới chưa seed, danh sách phải là `{"tasks":[]}`. Health 200 chỉ chứng minh schema/connection sẵn sàng, chưa chứng minh client Android hoặc sync.

Muốn kiểm tra ghi bằng HTTP độc lập trên DB mới, giữ nguyên key/body khi retry:

```bash
curl --max-time 5 -i -X POST http://127.0.0.1:3001/api/v1/tasks -H 'Content-Type: application/json' -H 'Idempotency-Key: 00000000-0000-4000-8000-000000011010' --data-binary '{"id":"00000000-0000-4000-8000-000000011001","baseVersion":0,"title":"B11 kiểm tra API từ host","description":"Task HTTP riêng, không phải thao tác Android","subjectCode":"MOBILE","dueDate":null,"priority":"MEDIUM","completed":false,"isDeleted":false}'
curl --max-time 5 -i http://127.0.0.1:3001/api/v1/tasks/00000000-0000-4000-8000-000000011001
```

POST lần đầu và retry nguyên request đều trả 201/snapshot version 1, chỉ có một receipt cho key này. UUID dành riêng cho kiểm tra host; không gán trực tiếp vào Room rồi trình bày là thao tác người dùng Android. Những kịch bản A/B phải dùng task mới và ghi lại UUID thực do app tạo.

Khi kết thúc, nhấn **Ctrl+C tại Terminal A** để dừng đúng server foreground đã tạo. Sau khi prompt trở lại, inspect lần cuối và dọn đúng thư mục tạm từ chính Terminal A:

```bash
node src/database/cli.js inspect
python3 - <<'PY'
import os, shutil
from pathlib import Path
p = Path(os.environ['STUDYFLOW_INTEGRATION_DIR'])
assert p.parent == Path('/private/tmp') and p.name.startswith('studyflow-integration.')
shutil.rmtree(p)
print('Đã dọn thư mục tích hợp riêng')
PY
unset DATABASE_PATH NODE_ENV HOST PORT STUDYFLOW_INTEGRATION_DIR
lsof -nP -iTCP:3001 -sTCP:LISTEN
```

Không dọn nếu còn cần A/B dùng lượt này; không chạy reset, pm clear, uninstall hoặc kill theo tên tiến trình. `lsof` không có output sau khi đóng nghĩa là không còn listener trên cổng đó tại thời điểm kiểm tra.

## Base URL và mạng thiết bị

| Nơi gọi | URL của server tích hợp ở trên |
|---|---|
| Host | `http://127.0.0.1:3001/api/v1/` |
| Android Emulator chuẩn | `http://10.0.2.2:3001/api/v1/` |
| Điện thoại thật | URL địa chỉ LAN của laptop in bởi lệnh bên dưới, cùng mạng và firewall cho phép |

`localhost`/127.0.0.1 trên Android là chính thiết bị; alias 10.0.2.2 là host loopback của Android Emulator, không áp dụng cho điện thoại thật. Đối chiếu [tài liệu emulator chính thức](https://developer.android.com/studio/run/emulator-networking-address).

Lệnh sau in các IPv4 đang có của laptop, không hardcode hoặc commit địa chỉ LAN cá nhân. Chọn giao diện cùng mạng với điện thoại, không tự suy ra VPN là LAN đã kết nối được. Lệnh adb yêu cầu platform-tools của SDK hiện có nằm trong PATH; nếu chưa cấu hình, dùng executable adb trong SDK của máy mình, không chép đường dẫn SDK của máy khác:

```bash
cd server
node --input-type=module -e 'import os from "node:os"; for (const [name, entries] of Object.entries(os.networkInterfaces())) for (const entry of entries ?? []) if (entry.family === "IPv4" && !entry.internal) console.log(`${name}: http://${entry.address}:3001/api/v1/`);'
adb devices -l
```

Hiện chưa có client thật hoặc cấu hình base URL trong app, nên không có Gradle flag đổi URL nào đang có hiệu lực. A cần cấu hình URL tại **một nơi** trong adapter/binding. Nếu chọn base URL kết thúc `/api/v1/`, endpoint tương đối là `tasks`; `MutationRequest.path` hiện đã chứa `/api/v1/...`, không nối thành `/api/v1/api/v1/...`. Phân tách query baseVersion cho DELETE, không chuyển sang body.

Manifest hiện chưa có quyền INTERNET; debug chưa có cấu hình cleartext. A cần thêm cấu hình phù hợp khi thêm network client thật. HTTP local chỉ cho phép trong debug source set/manifest đã merge; không mở cleartext ở main/release. Không nhầm `debug-overrides` (dùng cho CA khi HTTPS debug) với bật HTTP. Tham khảo [quyền và HTTP client](https://developer.android.com/develop/connectivity/network-ops/connecting), [network security configuration](https://developer.android.com/privacy-and-security/security-config). Chưa sửa manifest hoặc thêm dependency trong Bước 11 này.

Khi có thiết bị và client, gọi health từ browser/công cụ có sẵn trên từng thiết bị trước. Browser 200 chỉ là bằng chứng đường mạng, không chứng minh quyền mạng/app/Room. Không cài công cụ lên thiết bị hoặc dùng cURL trên host thay cho kiểm tra app.

## Phần bàn giao cho A trước khi ghép thật

Bảng dưới dùng đường dẫn tương đối với `android/app/src/main/java/com/studyflow/`; dependency/manifest tương ứng nằm trong `android/app/build.gradle.kts`, `android/gradle/libs.versions.toml` và `android/app/src/{main,debug}/AndroidManifest.xml`. Xem thêm [README Android](../android/README.md) để phân biệt kết quả fake/Room của các mốc trước với ghép API thật.

| Hạng mục/file hiện có | Tình trạng và việc A cần làm |
|---|---|
| `sync/api/TaskApi.kt` | Chỉ interface/DTO; cần adapter HTTP thực và parser kiểm tra đầy đủ field/status, null/boolean, lỗi currentTask/fieldErrors. Chưa có Retrofit/OkHttp dependency |
| `StudyFlowApplication.kt` | Chỉ tạo RoomTaskRepository; cần bind client thật + RoomSyncStore/SyncEngine vào luồng dùng chung, không gắn fake |
| `ui/sync/SyncScreen.kt` | Button enabled=false/onClick rỗng; cần nối cơ chế manual sync và trạng thái thật, không bật nút để giả đã đồng bộ |
| `SyncScheduler.kt`, `SyncWorker.kt` | File TODO, chưa có WorkManager dependency; không tuyên bố tự động sync. Nếu ưu tiên ghép manual trước, phải ghi rõ manual |
| `ConflictResolver.kt` và UI xác nhận | Resolver TODO, UI chọn phiên bản chưa ghi thật; cần giữ hai snapshot và kiểm tra lại version khi xác nhận |
| Storage A/B | Cần hai thiết bị hoặc hai AVD có userdata riêng; không phải hai cửa sổ dùng chung dữ liệu. Không cài đè/xóa dữ liệu app hiện có nếu chưa được phép |

Hai điểm tương thích phát hiện **qua đọc mã**, chưa phải kết quả chạy Android:

1. `SyncEngine.validAcknowledgement()` đang luôn đòi `server.version == sent.baseVersion + 1`. DELETE mới trên tombstone matched theo quyết định Bước 8 trả **cùng version**, nên có thể bị ghi INVALID_RESPONSE và retry receipt mãi. A cần regression cho ACK DELETE no-op, giữ nguyên bảo vệ revision/snapshot; không sửa server để tăng version giả.
2. `TaskSnapshot.isValid()` dùng `String.length` UTF-16 cho title/description. Backend Bước 5 đếm code point: 120 emoji là title hợp lệ nhưng Android hiện có thể từ chối cả pull. A cần đối chiếu bộ đếm/trim/Unicode, gồm NUL/surrogate, với fixture biên hiện có; không nới hợp đồng để che chênh lệch.

Những phần này thuộc client/engine của Android và cần hoàn thiện cùng adapter, không chỉ đổi một URL. Bước 11 không tự viết lại network stack, scheduler, UI hoặc kiến trúc đồng bộ để lấp khoảng trống. Không nhắn sang chat khác hoặc liên hệ A qua dịch vụ ngoài; đây là nội dung bàn giao trong repo.

## Hợp đồng phải giữ khi nối

- [Sync Protocol](../contracts/sync-protocol.md), [OpenAPI](../contracts/openapi.yaml), [13 JSON mẫu](../contracts/examples/README.md) là đầu vào chung, đã có dữ liệu thật trong repo.
- Android vẫn gửi rõ `includeDeleted=true`; default đã chốt true, false chỉ lấy task sống; query sai/lặp là 422.
- POST/PUT/DELETE có UUID `Idempotency-Key`. POST baseVersion 0/version 1; PUT không upsert; DELETE không body, xóa logic 200/tombstone.
- DELETE mutation mới trên tombstone matched giữ nguyên task/version/time nhưng ghi receipt mới. Stale version trả 409/currentTask đầy đủ. Không dùng giờ thiết bị chọn bản thắng.
- Retry giữ nguyên method/path/query/body/mutation ID. Receipt được kiểm tra trước version và trả status/body snapshot cũ, kể cả task đã sửa/phục hồi tiếp. Không dựng response từ task hiện tại, không double serialize JSON.
- Log backend có request ID mới mỗi lần gửi, mutation ID giữ qua retry. Xem event mutation.completed/outcome applied, replay, accepted_noop; taskChanged=false cho replay/no-op. Finish ở server không chứng minh client nhận được; receipt/DB mới là bằng chứng commit.

## Kiểm tra và điều kiện mở lượt hai thiết bị

```bash
cd server
npm test
npm run test:smoke
npm run test:faults -- --scenario=lost-response
```

Ba lệnh dùng DB tạm độc lập; fault harness không được trỏ sang server/DB chung hoặc thiết bị Android. Nó chứng minh backend retry, chưa tạo được lỗi response cho Android. Muốn kiểm tra retry của app, A cần một lượt test có kiểm soát riêng và bằng chứng outbox/mutation của app, không dùng fake hoặc sửa DB thủ công làm minh chứng.

Kiểm tra Wrapper hiện có (không tải mới):

```bash
cd android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline --no-daemon -Dorg.gradle.java.installations.auto-download=false --version
```

Sau khi A có sửa mã tích hợp, dùng Wrapper `:app:assembleDebug :app:testDebugUnitTest`; test thiết bị chỉ chạy trên thiết bị/test storage được phép. Không coi build thành công hoặc 82 unit test fake ở mốc cũ là kết quả ghép server thật. Xem [ma trận kịch bản](testing/test-cases.md), [kết quả thực tế](testing/test-results.md) và [thứ tự demo](demo/demo-script.md).

## Tái hiện bàn giao Bước 13

Lượt Bước 13 cài mới bằng `npm ci` trong bản sao riêng từ HEAD `da465da` và chép đè các Markdown chưa commit, gồm phần bàn giao Bước 11. Không mang theo `.env`, node_modules hay DB của máy làm việc. Cấu hình riêng loopback/cổng trống và DB mới, rồi chạy script migrate → seed → inspect → start cùng các kiểm tra API/test/fault. Lệnh và kết quả thực tế, lỗi môi trường cùng mức kiểm tra OpenAPI được ghi ở [test-results](testing/test-results.md#bước-13--tái-hiện-và-bàn-giao-backend-30102026). Đây không phải xác nhận remote HEAD đã chứa tài liệu bàn giao hoặc Android đã tích hợp.

Người nhận bắt đầu từ [README server](../server/README.md), đọc [kiến trúc](architecture.md)/[database](database-design.md), đối chiếu [hợp đồng](../contracts/sync-protocol.md) trước khi nối app. [Bảng D1–D8](report/outline.md#7-đối-chiếu-d1d8-và-phân-công) ghi vai trò B/A/cả nhóm và đầu ra còn thiếu. Không cần reset DB demo để thực hiện các test độc lập.
