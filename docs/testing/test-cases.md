# Các ca kiểm thử

## Audit backend trước Bước 9

Đối chiếu ngày 30/10/2026 với [hợp đồng đồng bộ](../../contracts/sync-protocol.md), [OpenAPI](../../contracts/openapi.yaml), JSON mẫu và mã thực tế. Bộ test Bước 4–8 đã chạy lại: 136 đạt, 0 lỗi, 0 bỏ qua. Bảng này được lập trước khi bổ sung test; “khoảng trống” mô tả trạng thái đầu Bước 9, không phủ nhận các kiểm tra ở tầng thấp hơn.

| Quy tắc hợp đồng | Test đã có trước Bước 9 | Mức kiểm tra | Khoảng trống cần bổ sung |
|---|---|---|---|
| Trường bắt buộc, trim/độ dài, Unicode, null/kiểu/enum, ngày thực/năm nhuận, boolean, UUID, baseVersion | `validation.test.js`, `http.test.js`; một số ca production trong `task-write-api.test.js` | Unit, HTTP adapter test; HTTP app chính | Ca biên đại diện qua route ghi thật, xác minh không ghi task/receipt khi 422; không lặp toàn bộ unit matrix |
| Envelope, camelCase, boolean/null, không lộ metadata và lỗi kín | `responses.test.js`, `read-api.test.js`, `task-write-api.test.js` | Mapper + SQLite, HTTP app chính | Đối chiếu schema OpenAPI với response HTTP thật, thay vì chỉ assert thủ công |
| GET rỗng/default true/false/tombstone/không trùng ID; detail 200/404/422; health 200/503 | `read-api.test.js`, `task-repository.test.js` | HTTP app chính; database | Đã bao phủ; giữ lại. Snapshot đọc dùng một SELECT; test WAL hai connection kiểm tra trước/sau commit, chưa ép commit chen giữa SELECT |
| POST UUID/v1, ID sống/tombstone tồn tại conflict; PUT đủ trường, no-upsert/version/restore | `task-write-api.test.js` | HTTP app chính + SQLite thật | Đã bao phủ; dùng lại khi đối chiếu schema |
| DELETE xóa logic 200; tombstone version khớp no-op có receipt; stale conflict; retry sau phục hồi | `task-write-api.test.js` | HTTP app chính + SQLite thật | Đã bao phủ; kiểm tra thêm tính bền vững qua restart |
| Fingerprint key order, array/query, thiếu/null/false/số/chuỗi và raw payload | `request-fingerprint.test.js`, `mutation-http.test.js`, `task-write-api.test.js` | Unit, HTTP test app và app chính | Key order và phân biệt kiểu qua production HTTP |
| Receipt trước version; replay status/body cũ dù task đổi tiếp | `mutation-executor.test.js`, `task-write-api.test.js` | Executor + SQLite; HTTP app chính | Replay POST/PUT/DELETE/no-op qua HTTP sau restart process |
| Task/receipt cùng commit/rollback, lỗi nghiệp vụ không có receipt, mất response sau commit | `database.test.js`, `mutation-executor.test.js`, `mutation-http.test.js`, `task-write-api.test.js` | Database; HTTP app chính với lỗi SQLite thật và mất response | Đã bao phủ; không xây proxy/công cụ Bước 10 |
| Task/tombstone/receipt bền vững, không phụ thuộc cache RAM | `database.test.js` đọc bằng child process; `mutation-executor.test.js` reopen connection | Nhiều process đọc SQLite; executor | Production HTTP replay cả ba method và no-op sau restart, task hiện tại vẫn đúng |
| Receipt hỏng không khiến áp dụng lại | `mutation-executor.test.js` | Executor + SQLite | App chính trả 500 kín qua HTTP và không đổi database |
| Hai mutation khác key cùng version; cùng key/request chỉ áp dụng một lần | `task-write-concurrency.test.js`, `mutation-executor.test.js` + worker helpers | Hai Worker/hai connection SQLite, có tranh khóa thật và retry sau BUSY | Cùng key nhưng khác request: loser nhận MUTATION_ID_REUSED, chỉ winner được áp dụng |
| YAML/$ref/parameter/schema/JSON mẫu, default includeDeleted và DELETE tombstone nhất quán | Script giới hạn ngoài repo ở Bước 6–8; fixture assertions trong tests | Kiểm tra cấu trúc riêng, mapper/HTTP | Đưa kiểm tra hợp đồng vào lệnh test có thể chạy lại; nêu rõ giới hạn validator |

## Phạm vi và trạng thái

Đây là audit và bộ ca backend độc lập. Sau audit đã giữ nguyên 136 test cũ và bổ sung 11 test Node; kết quả thực tế ở [test-results.md](test-results.md). Một test Node có thể kiểm tra nhiều trường hợp trong bảng; số dòng ca nghiệp vụ không phải số test Node.

## Các ca quan trọng sau bổ sung

| Mã | Thao tác và kết quả cần kiểm tra | Tự động hóa hiện tại | Trạng thái ngày 30/10/2026 |
|---|---|---|---|
| BE-01 | POST/PUT đầy đủ trường; trim, biên title 120/121, description 2000/2001, tiếng Việt/emoji/dấu tách rời; không normalize | Unit validation; `write-http-contract.test.js` qua app thật | Đã chạy tự động, đạt |
| BE-02 | Thiếu/null/sai kiểu, enum/ngày/năm nhuận, boolean chuỗi/số, UUID/header, ID PUT khác URL, baseVersion âm/thập phân/unsafe/query lặp | `validation.test.js`, `http.test.js`, `read-api.test.js`, `task-write-api.test.js`, HTTP bổ sung | Đã chạy tự động, đạt |
| BE-03 | GET rỗng, default/true có tombstone, false chỉ bản sống; detail sống/tombstone/404/422; không ghi DB/receipt | `read-api.test.js`, `task-repository.test.js` | Đã chạy tự động, đạt |
| BE-04 | Snapshot WAL trước/sau commit trên hai connection; mỗi GET dùng một SELECT | `task-repository.test.js` | Đã chạy database test; chưa ép commit chen giữa SELECT |
| BE-05 | Health schema sẵn sàng 200; thiếu/đóng DB 503; body rỗng, không coi 200 là toàn bộ nghiệp vụ đúng | `http.test.js`, `read-api.test.js`, `openapi.test.js` | Đã chạy tự động, đạt |
| BE-06 | POST giữ UUID/version 1; ID sống hoặc tombstone đã có nhận 409, không tái tạo | `task-write-api.test.js` | Đã chạy HTTP tự động, đạt |
| BE-07 | PUT đầy đủ false/chuỗi rỗng/null; không upsert; matched tăng 1, stale có currentTask đầy đủ; PUT hợp lệ phục hồi tombstone | `task-write-api.test.js` | Đã chạy HTTP tự động, đạt |
| BE-08 | DELETE sống giữ nội dung, xóa logic 200; tombstone matched không đổi task/version/time nhưng thêm receipt; stale conflict; replay sau phục hồi không xóa lại | `task-write-api.test.js`, `restart-replay.test.js`, `openapi.test.js` | Đã chạy HTTP/process, đạt |
| BE-09 | Receipt trước version; status/body snapshot cũ cho POST/PUT/DELETE; đổi method/path/query/payload/baseVersion cùng key bị MUTATION_ID_REUSED | Executor và write API tests | Đã chạy tự động, đạt |
| BE-10 | Đảo key JSON đệ quy vẫn replay; thiếu/null/false/số/chuỗi/thứ tự array khác nhau; hash cả trường không được whitelist ghi | `request-fingerprint.test.js`, `write-http-contract.test.js` | Đã chạy unit + production HTTP, đạt |
| BE-11 | Callback/nghiệp vụ/INSERT receipt/COMMIT lỗi: không có task/receipt thành công một phần; mất response sau commit: retry không áp dụng lại | `database.test.js`, executor/mutation HTTP/write API tests | Đã chạy SQLite thật + HTTP, đạt |
| BE-12 | Process A ghi hai task/bảy receipt, gồm tombstone/no-op/restore; dừng A, process B replay bảy snapshot; task sống v4/tombstone v2 và toàn bộ receipt/migration nguyên trạng | `restart-replay.test.js`, helper `http-process.js` | Đã chạy hai process qua HTTP, đạt; đây là restart, không phải concurrency |
| BE-13 | JSON/schema/hash/path/time receipt bị hỏng: HTTP 500 kín, không chạy lại mutation, không đổi DB | `write-http-contract.test.js`; fault chỉ ở DB test riêng | Đã chạy tự động, đạt |
| BE-14 | Hai worker: khác key/cùng baseVersion → một PUT thành công, bên retry conflict; cùng key/cùng request → replay; cùng key/khác request → MUTATION_ID_REUSED | `task-write-concurrency.test.js`, TEMP trigger/gate chỉ dành cho test | Đã chạy 3 ca tranh khóa thật và thêm 5 lượt đánh giá phối hợp, đều đạt |
| BE-15 | YAML không trùng key; local refs/parameter/default; mọi JSON mẫu và HTTP response theo schema hiện có; Content-Type/envelope/kiểu JSON đúng | `openapi.test.js`, helper giới hạn `openapi-contract.js` | Đã chạy 5 test, đạt; không phải validator OpenAPI đầy đủ |

## Cách hoạt động và giới hạn

- Unit test gọi một hàm với dữ liệu biên để kiểm tra một quy tắc. Integration HTTP gửi request qua production `createApp`, parser/router/controller/service/executor và SQLite đã migrate, rồi so status/header/body và trạng thái database.
- Database test kiểm tra trực tiếp CHECK/UNIQUE/transaction/persistence. File SQLite tạm hoặc `:memory:` tách riêng cho mỗi ca/nhóm; không đọc `.env`, không dùng/reset/seed lại database demo. Server mới dùng port động; cleanup đóng kết nối/server/worker và xóa đúng thư mục test kể cả assertion thất bại.
- Với concurrency, writer giữ transaction bằng gate test; worker thứ hai phải thực sự nhận SQLITE_BUSY trước khi writer commit. Sau đó retry đúng request/key: BUSY là tranh khóa tạm thời, VERSION_CONFLICT là version đã cũ, replay là receipt cùng request, MUTATION_ID_REUSED là receipt khác request. Không gọi hai lời gọi sync nối tiếp hay Promise.all một event loop là nhiều SQLite writer.
- Fault trước commit dùng callback/trigger/constraint SQLite thật trong DB test; rollback phải giữ trạng thái trước request. Mất response sau commit dùng transport wrapper ở test và app production, rồi retry receipt. Không thêm API gây lỗi/reset vào production hoặc xây proxy Bước 10.
- OpenAPI dùng parser YAML và evaluator subset: local refs, allOf/oneOf, type/enum/required/nullable/properties/additionalProperties/items, giới hạn số/độ dài code point và UUID/date/UTC. Keyword chưa hỗ trợ làm test lỗi thay vì bị bỏ qua. Không kiểm tra toàn bộ OpenAPI semantics/JSON Schema/RFC 3339; offsets/leap seconds không thuộc format UTC đang dùng. Unknown fields do OpenAPI cho phép không tự bị cấm; test envelope/metadata chặt hơn vẫn được giữ riêng.
- Chưa thực hiện stress HTTP, benchmark, forced crash/kill ngay giữa COMMIT, hoặc mô phỏng mọi lịch interleaving. Persistence ở đây là đóng tiến trình bình thường rồi khởi động mới, không phải bằng chứng chịu mất điện.
- Room, outbox/SyncEngine, WorkManager, giao diện xung đột và hội tụ hai thiết bị Android chưa được kiểm chứng bởi các ca backend này. Các ca đó cần bộ kiểm thử và thiết bị riêng; không đánh dấu đạt từ kết quả backend.

## Audit trước Bước 10

Ngày 30/10/2026, trước khi thêm mã công cụ/log: Bước 7–9 đã có test SQLite rollback callback/receipt INSERT/COMMIT, production HTTP mất response sau POST commit, restart/replay và tranh khóa Worker. Khoảng trống là tái hiện được bằng lệnh với bằng chứng đọc được và correlation log; không cần viết lại các test đó.

| Nội dung | Hiện có | Phần bổ sung dự kiến |
|---|---|---|
| Rollback/lỗi tạm thời trước commit | Trigger/constraint SQLite thật trong test | Tái sử dụng trigger trên DB tạm của harness; 500/no receipt rồi retry đúng snapshot |
| Mất response sau commit | Transport wrapper test | Proxy cục bộ nhận đủ upstream response rồi giữ/phá downstream; xác minh receipt và replay |
| Restart/persistence | Child production app, IPC port động/shutdown | Tái sử dụng helper; dừng đúng child, quan sát lỗi kết nối rồi mở lại cùng DB |
| Timeout | HTTP test có deadline bảo vệ | Gate trước dispatch và gate sau upstream response, phân biệt trạng thái bằng tín hiệu + SQLite, không đoán commit bằng sleep |
| Log | Chỉ lỗi bất ngờ/health, chưa có request ID/success/replay/duration | JSON có cấu trúc, UUID server cho mỗi request, mutation ID giữ qua retry, duration monotonic, outcome sau transaction |
| An toàn logger | Sink error đang gọi trực tiếp; stack chứa đường dẫn nội bộ | Chặn lỗi sink; rút gọn diagnostic; không để logging thay đổi response/task/receipt |

Chọn harness Node nhỏ và proxy cấu hình bằng closure, không thêm endpoint, header/query điều khiển fault, framework hoặc dependency. Mỗi lượt sở hữu DB/port/process riêng, có timeout tổng và cleanup trong finally. Log chỉ phản ánh kết quả đã biết: committed/replay sau executor, finish ở server khác client nhận được, close không tự chứng minh rollback.

## Các ca Bước 10

Các ca dưới đây dùng app production, migration thật và SQLite tạm. Chạy từ `/Users/tuankhong/Documents/StudyFlow/server`; `npm run test:faults` chạy cả năm, hoặc dùng lệnh riêng trong [kịch bản demo](../demo/demo-script.md). Không cần `.env` hoặc dừng server đang chạy của người dùng.

| Mã | Điều kiện/thao tác | Kết quả phải xác minh |
|---|---|---|
| BE-16 | `unavailable`: child đã ghi, dừng đúng child rồi gọi lại; restart trên cùng file | Lỗi kết nối không có HTTP status, dữ liệu bền vững, retry nguyên snapshot nhận receipt |
| BE-17 | `timeout-before-commit`: proxy giữ trước dispatch, client hết timeout; hủy request bị giữ rồi retry | Tín hiệu chứng minh chưa dispatch; không có request ID production hoặc task/receipt mới cho lần timeout; retry được áp dụng một lần |
| BE-18 | `transient-before-commit`: trigger test làm receipt INSERT lỗi rồi gỡ trigger | 500 tạm thời; task/receipt cùng rollback; retry đúng mutation có thể áp dụng mới, không bị 409/422 giả |
| BE-19 | `timeout-after-commit`: giữ downstream sau khi đã nhận đủ response upstream | Receipt đã commit, client timeout; retry snapshot cũ không tăng version/time/receipt |
| BE-20 | `lost-response`: proxy nhận upstream rồi phá downstream trước success bytes; sửa task bằng mutation mới rồi retry mutation cũ | Client không nhận success đầu, replay status/body cũ chính xác, task hiện tại mới hơn vẫn nguyên |
| BE-21 | Log HTTP production cho áp dụng mới/replay/no-op/conflict/validation/lỗi tạm thời | Request ID là UUID khác nhau mỗi lần gửi; mutation ID giữ qua retry; applied sau commit, replay không là lần ghi task mới |
| BE-22 | Logger ném lỗi/reject; SQLite receipt INSERT/COMMIT lỗi; response đóng/lỗi sau commit | Logging không đổi response hoặc atomicity; không log commit trước commit thật, không log rollback chỉ vì socket đóng |
| BE-23 | Payload/query/header/ID không hợp lệ và chuỗi đầu vào có ký tự điều khiển | Log JSON an toàn, không chứa body/title/description/query/env/full internal path; chỉ ghi task/mutation UUID xác định được, không tin request ID do client gửi |
| BE-24 | Assertion lỗi giữa lượt harness, CLI argument sai và production start/dev | Cleanup đúng child/proxy/DB/thư mục tạm kể cả failure; exit code khác 0 khi assertion lỗi; start/dev không import harness hoặc bật fault bằng header/query |

Mỗi kịch bản có trạng thái trước/sau và assertion dựa trên SQLite/response, không chỉ dựa vào log “replay”. Công cụ không lưu evidence mặc định; JSON stdout nêu scenario, lỗi client, mutation/request ID, số task/receipt, version/time và kết quả kiểm chứng. Các duration dùng cho chẩn đoán, không phải benchmark.

## Bước 11: bàn giao và ma trận ghép hai thiết bị

Điều kiện/lệnh môi trường riêng ở [setup.md](../setup.md). Lượt 30/10/2026 chưa có HTTP adapter/binding thật hoặc hai thiết bị, nên các ca Android dưới đây là **đã thiết kế, chưa chạy**. Backend đã kiểm tra riêng không thay thế cột kết quả Android. Không tự tạo fixture trong Room hoặc sửa database để coi là thao tác người dùng.

Điều kiện mở lượt: hai thiết bị/AVD với app storage riêng, variant debug thật đã nối API, cùng server/DB test mới, không pending/conflict cũ trên các task liên quan. Dùng tên task `B11-A`, `B11-B`… để nhận diện; UUID phải lấy từ task/outbox do app tạo và ghi lại, không đoán ID từ tên. Ghi rõ đồng bộ manual hay tự động; chỉ đánh giá WorkManager nếu đã triển khai và quan sát được.

| Mã | Điều kiện ban đầu và thao tác dự kiến | Kết quả kỳ vọng / bằng chứng cần có | Kết quả Bước 11 này |
|---|---|---|---|
| IT-00 | Server riêng, DB migrate mới; host gọi health và CRUD bằng HTTP | Health 200 rỗng; UUID/version/tombstone/receipt đúng; log và đọc SQLite test | Đã chạy host: 15 cURL request + 11 smoke, đạt; không phải Android |
| IT-01 | Kiểm kê client/variant và thiết bị | Adapter thật được bind, URL tập trung, INTERNET/debug HTTP phù hợp, hai storage độc lập | Đã audit: thiếu adapter/binding/config, ADB không có device; chưa đạt điều kiện mở lượt |
| IT-02 | Health từ từng thiết bị trước khi dùng app | Host 200, browser/công cụ trên A/B truy cập được; app còn cần kiểm tra riêng permission và client | Chưa chạy: không có A/B; host health không chứng minh device health |
| IT-A | A tạo `B11-A`, UI đọc Room; sync A, đọc API cùng UUID/version 1; sync B | A lưu cục bộ trước HTTP, server có receipt, B nhập cùng UUID/nội dung; Room/outbox/log riêng A/B | Chưa chạy: thiếu client/binding và hai thiết bị |
| IT-B | A/B cùng task v1 sạch; chỉ A sửa, sync A rồi B | Server v2; B nhận bản mới, không conflict; so trường nghiệp vụ và metadata pending liên quan | Chưa chạy: cùng điều kiện IT-A |
| IT-C | Hai task đã có trên A/B; A sửa task thứ nhất, B sửa task thứ hai rồi sync | Mỗi ID tăng version đúng, không conflict giữa ID khác nhau | Chưa chạy: cùng điều kiện IT-A |
| IT-D | Chỉ A offline; tạo/sửa/hoàn thành task, đóng/mở app rồi nối lại mạng và sync | Dữ liệu cục bộ bền vững, pending còn khi offline; B nhận sau sync; ghi rõ manual, không hứa worker chạy ngay | Chưa chạy: client/scheduler chưa nối, không có thiết bị |
| IT-E | A/B có task sống cùng version; A xóa offline rồi sync, B pull và sync thêm lần nữa | Server tombstone giữ nội dung; B không hiển thị sống, không tự xuất hiện lại; kiểm isDeleted trong dữ liệu, không chỉ dựa vào list vắng ID | Chưa chạy: cùng điều kiện IT-D |
| IT-F | A/B cùng task/version, sửa độc lập; A sync trước, B sau; B chọn bản server | 409/currentTask đầy đủ; giữ hai snapshot; xác nhận đúng bản đã xem, không mất local trước quyết định | Chưa chạy: thiếu binding và resolver/UI ghi thật |
| IT-G | Task riêng, xung đột tương tự IT-F; chọn bản thiết bị | PUT mới, mutation mới, baseVersion của bản đã xem; hai phía nhận bản được chấp nhận | Chưa chạy: thiếu resolver/UI ghi thật |
| IT-H | Khi đang xem conflict, phía kia sửa server tiếp rồi xác nhận lựa chọn cũ | Precondition cũ không ghi đè bản server mới; hiển thị bản mới để xem lại | Chưa chạy: thiếu resolver và hai thiết bị |
| IT-I | Hai phía sửa thành cùng nội dung; task khác cho xóa–sửa | Hội tụ không hỏi chọn không cần thiết; xóa–sửa theo lựa chọn toàn bộ, không tự gộp từng trường | Chưa chạy; logic fake/unit cũ không được tính là kết quả API thật |
| IT-J | Client thật gửi snapshot, server commit nhưng app chưa nhận response trong môi trường lỗi riêng được phép | Outbox giữ nguyên method/path/query/body/mutation ID; request ID đổi; retry receipt không tăng version/time; đối chiếu app outbox + backend log/receipt | Chưa chạy: không có adapter/app binding; Bước 10 chỉ chứng minh backend |
| IT-K | Gửi revision r, người dùng sửa tiếp r+1 trước response | ACK chỉ xác nhận r; không ghi đè hoặc đánh dấu sạch r+1; pending tiếp bằng mutation mới | Chưa chạy: không có đường gửi thật/UI binding |
| IT-L | Mọi task liên quan đã hết pending/conflict | So tập ID, title/description/subjectCode/dueDate/priority/completed/isDeleted giữa Room A, Room B và API; không chỉ số lượng/UI | Chưa chạy: không có Room A/B để đọc; không suy ra từ ảnh UI |
| IT-M | Trước adapter, đối chiếu ACK tombstone no-op và Unicode DTO | DELETE no-op baseVersion giữ nguyên phải ACK đúng; title/description đếm code point thống nhất, không từ chối valid pull | Đã phát hiện chênh lệch qua mã; bàn giao A thêm regression/sửa client, chưa chạy Android |

Sau khi A hoàn thiện: mỗi ca ghi serial/variant, tên DB server riêng, UUID thực của task, baseVersion đầu, pending/conflict A/B, thao tác, expected/actual và nguồn bằng chứng. App/UI, HTTP, log server và truy vấn DB read-only là các nguồn khác nhau; không ghép chúng thành một bằng chứng giả.

Không dùng `test:faults` như proxy chờ Android: công cụ đó tự tạo backend/DB/request và tự dọn, không có chế độ nhận mutation từ app. Không gây lỗi trên server dùng chung; chỉ mở ca IT-J sau khi xác định được phạm vi và công cụ phù hợp với client thật. Không reset server/Room A/B, cài đè hoặc pm clear nếu chưa có phép.

## Bước 13 — kiểm tra khả năng bàn giao

Các kiểm tra này chạy ngoài repo runtime, không thêm endpoint test vào app. [Kết quả thực tế](test-results.md#bước-13--tái-hiện-và-bàn-giao-backend-30102026) ghi lệnh, snapshot và trạng thái từng phần.

| Mục | Cách kiểm / expected |
|---|---|
| Mã nguồn sạch có kiểm soát | Archive HEAD rồi overlay toàn bộ thay đổi tài liệu đang có; không sao chép `.env`, node_modules hoặc DB; ghi rõ tài liệu chưa commit |
| Cài và cấu hình | npm ci theo lockfile, native driver nạp được; cấu hình từ mẫu cho loopback/cổng trống/DB mới; guard không chép đè `.env` đã có |
| Migration/seed/inspect | Schema mới, ba task cố định (một tombstone), 0 receipt, integrity ok; migrate/seed chạy lại không đổi snapshot/checksum |
| API chính và hợp đồng | Health/list/detail/POST/PUT/DELETE thực qua npm start; 404/409/422, currentTask đầy đủ, default/filter/query lặp; response được kiểm bằng evaluator OpenAPI hiện có |
| Replay và xóa logic | POST v1 → PUT v2 → DELETE v3 → no-op v3 → phục hồi v4; retry snapshot POST/DELETE cũ không đổi state, không có receipt lỗi |
| Test và công cụ lỗi | npm test đủ bộ, test:smoke, test:faults đủ năm scenario, cleanup tài nguyên riêng |
| Lỗi vận hành | Thiếu env, PORT sai, DB/schema thiếu, cổng bận do checker giữ, checksum thay đổi trong bản sao: từ chối đúng, không sửa dữ liệu demo |
| Kiểm kê bàn giao | Link/lệnh đúng file/script, source/lock/fixtures được theo dõi, diff không runtime/secret, D7 chưa đo vẫn chưa đo; Android/A/B/APK/video chưa được nâng thành pass |
