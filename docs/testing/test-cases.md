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
