# Kịch bản demo

Các lệnh có `cd server` bắt đầu từ gốc repo trong terminal mới; nếu đã ở `server/` thì bỏ dòng đó. Chạy theo [README server](../../server/README.md) với Node 22 và lockfile. [Kết quả](../testing/test-results.md) là các lượt đã chạy; các bước A/B phía dưới vẫn là kế hoạch.

## Demo lỗi và retry backend độc lập — Bước 10

Chạy trong terminal với Node theo engines của server; máy mới chạy `npm ci` trước. Không cần Android, `.env` hoặc database demo. Mỗi lượt công cụ tự tạo file SQLite tạm, chạy migration thật, mở backend/proxy trên port động và chỉ quản lý tài nguyên của chính lượt đó.

```bash
cd server
npm run test:faults
```

Công cụ chạy lần lượt năm kịch bản dưới đây rồi thoát. Không khởi động hoặc dừng server của người khác. Không tự lưu evidence/database/log vào repo. Các dòng JSON là bằng chứng từ lần chạy, không phải dữ liệu mẫu cần sửa hoặc nhập tay.

| Kịch bản | Điều kiện ban đầu và lệnh chạy riêng từ `server/` | Diễn biến cần quan sát | Xác minh |
|---|---|---|---|
| Server không sẵn sàng | DB tạm mới; `npm run test:faults -- --scenario=unavailable` | Ghi thành công bằng child do công cụ tạo, dừng child, retry bị lỗi kết nối; mở child mới cùng file và retry nguyên snapshot | Lỗi kết nối không có HTTP status; task/receipt cũ tồn tại; request mới có request ID khác, mutation ID không đổi, response replay snapshot |
| Timeout trước commit | DB tạm mới; `npm run test:faults -- --scenario=timeout-before-commit` | Proxy giữ request trước khi gửi upstream; client hết timeout; xác nhận chưa dispatch rồi bỏ request bị giữ | Không có request ID production cho lần chưa đến app; task/receipt chưa thay đổi; gửi lại đúng snapshot được áp dụng một lần |
| Lỗi tạm thời trước commit | DB tạm mới; `npm run test:faults -- --scenario=transient-before-commit` | Trigger chỉ trong DB tạm làm INSERT receipt lỗi; client nhận 500; harness bỏ trigger rồi retry | Task và receipt cùng rollback, không có thành công một phần; retry cùng key có thể áp dụng mới; lỗi không bị biến thành 409/422 |
| Timeout sau commit | DB tạm mới; `npm run test:faults -- --scenario=timeout-after-commit` | Proxy đã nhận đầy đủ upstream response nhưng giữ downstream cho client timeout | Receipt chứng minh đã commit, không dựa vào thời gian chờ; retry trả status/body cũ, không tăng version/time/receipt |
| Commit nhưng mất response | DB tạm mới; `npm run test:faults -- --scenario=lost-response` | Proxy nhận upstream thành công rồi phá downstream trước success bytes; sau đó sửa task bằng mutation mới và retry mutation cũ | Client không nhận được success đầu; task/receipt đã commit; retry nhận snapshot cũ dù task hiện tại mới hơn, không ghi đè task mới |

Khi chạy cả năm trong một lượt, từng kịch bản dùng UUID/request snapshot riêng và kiểm tra trạng thái trước/sau rõ ràng trên DB tạm của lượt đó. Retry dùng lại nguyên method/path/query/body/mutation ID đã lưu; không dựng lại request với giá trị ngẫu nhiên.

## Thứ tự trình bày

1. Mở [hợp đồng](../../contracts/sync-protocol.md), nhắc receipt phải được kiểm tra trước version và task/receipt cùng transaction.
2. Chạy `npm run test:faults` hoặc một lệnh kịch bản riêng ở bảng. Đọc tên scenario, lỗi client, request ID/mutation ID, status, version và số receipt trước/sau.
3. Ở ca trọng tâm `lost-response`, chỉ ra lần đầu client không có response thành công nhưng SQLite đã có receipt. Lần retry nhận chính snapshot đã lưu, trong khi task hiện tại vẫn giữ bản được sửa tiếp.
4. Đọc outcome log: áp dụng mới khác replay; request ID đổi mỗi lần gửi, mutation ID giữ nguyên qua retry. Replay không được đếm là một lần ghi task mới.
5. Kết thúc bằng kết quả assertion và cleanup của công cụ. Chỉ kết luận đạt nếu lệnh exit code 0 và các kiểm tra quan trọng đều PASS.

Timeout chỉ nói rằng client chưa nhận được kết quả trong thời hạn; không tự cho biết server đã lưu hay chưa. Công cụ phân biệt bằng gate trước dispatch hoặc upstream response cùng receipt trong SQLite. `finish` ở server không chứng minh client nhận được; socket đóng không chứng minh transaction rollback.

Proxy/fault chỉ nằm ở thư mục công cụ/test, cấu hình bằng JavaScript trong harness; không có header/query gây lỗi cho client bình thường. `npm start` và `npm run dev` vẫn chạy API production. Không có endpoint reset/gây lỗi mới.

## Dọn môi trường và giới hạn

Mỗi kịch bản có timeout hữu hạn; cả lượt có deadline tổng. Công cụ tự đóng proxy/connection, dừng đúng child đã tạo và xóa đúng thư mục tạm trong finally, kể cả assertion lỗi. Không cần tự đoán PID, chạy kill hàng loạt hoặc reset database demo. Nếu assertion/cleanup lỗi, lệnh phải trả exit code khác 0; giữ nguyên mutation ID trong bằng chứng để điều tra.

Kết quả đã chạy thật được ghi ở [test-results.md](../testing/test-results.md). Các thời lượng chỉ để chẩn đoán, không phải benchmark. Demo này chưa kiểm chứng Room, WorkManager, SyncEngine hoặc hội tụ hai thiết bị Android; phần ghép thử đó thuộc Bước 11.

## Bàn giao và demo ghép Android — Bước 11

**Trạng thái hiện tại:** mới chuẩn bị được backend/bàn giao. App chưa có HTTP adapter/binding thật, scheduler/worker/resolver còn thiếu và lượt kiểm kê Bước 11 không có thiết bị ADB. Không chạy các bước A/B dưới đây để tuyên bố pass bằng cURL hoặc fake. Expected/actual và lý do chưa chạy ở [ma trận IT](../testing/test-cases.md#bước-11-bàn-giao-và-ma-trận-ghép-hai-thiết-bị), kết quả host riêng ở [test-results](../testing/test-results.md).

### Phần có thể tái hiện ngay

1. Mở [setup.md](../setup.md), chạy Terminal A để tạo **DB tích hợp mới**, migrate và server 0.0.0.0:3001; nếu cổng bận thì không dừng listener khác.
2. Terminal B chạy hai cURL health/list trong setup. Nếu muốn demo CRUD host, dùng UUID/key riêng đã cho; nói rõ đây là kiểm tra API, chưa có Android. Danh sách rỗng hoặc health 200 không chứng minh sync.
3. Mở Sync Protocol/OpenAPI/JSON mẫu, trình bày POST version 1, PUT version precondition, DELETE tombstone/no-op, Idempotency-Key và snapshot replay. Đối chiếu log request ID với mutation ID.
4. Dừng đúng server foreground bằng Ctrl+C ở Terminal A; inspect read-only rồi dọn đúng thư mục tạm bằng lệnh trong setup. Không reset database demo hoặc đụng app data.

### Điều kiện trước khi trình bày A/B

- A hoàn thiện adapter thật và một cấu hình URL tập trung; emulator chuẩn dùng `http://10.0.2.2:3001/api/v1/`, điện thoại dùng LAN phù hợp, không phải localhost. Health phải kiểm tra từ từng thiết bị, rồi mới kiểm tra app permission/debug HTTP/client.
- Application/nút manual sync phải gọi engine/store thật; ghi rõ nếu chỉ manual. Không tuyên bố WorkManager đạt khi scheduler/worker còn TODO.
- Có hai thiết bị/AVD với userdata riêng; không cài đè/xóa app sẵn có nếu chưa được phép. Kiểm tra pending/conflict ban đầu và ghi lại serial/variant, DB server của lượt đó.
- A xử lý ACK DELETE tombstone không tăng version và bộ đếm Unicode đã nêu trong setup; chuẩn bị nguồn bằng chứng đọc Room/outbox hợp lệ. Resolver phải kiểm tra version lại khi xác nhận, không chỉ chọn một thẻ UI.

### Thứ tự thao tác sau khi đủ điều kiện — hiện chưa chạy

1. A tạo task `B11-A` qua UI; chứng minh lưu Room trước HTTP, ghi UUID thực. Đồng bộ A, đọc API/receipt cùng ID; đồng bộ B, kiểm UUID/nội dung trong nguồn dữ liệu của B.
2. Với task sạch trên hai phía, chỉ A sửa rồi sync A/B: B tự nhận, không có conflict không cần thiết. Dùng hai task riêng cho trường hợp A/B sửa khác ID.
3. Chỉ A offline, tạo/sửa/hoàn thành và xác nhận bền vững cục bộ; nối mạng, sync theo cơ chế thật. Dùng task khác cho xóa offline: server tombstone, B nhận xóa và sync thêm không tự xuất hiện lại.
4. Hai phía cùng version gốc, sửa khác nhau; sync một phía rồi phía kia. Kiểm 409/currentTask và hai snapshot. Dùng các task riêng cho giữ server, giữ thiết bị, server đổi tiếp trước xác nhận, cùng nội dung và xóa–sửa.
5. Chỉ khi đã có công cụ phù hợp cho **app thật trong môi trường riêng được phép**, thử commit mất response và sửa tiếp khi đang gửi. Công cụ `test:faults` hiện tự điều phối request riêng, không phải proxy chờ Android; không dùng kết quả của nó thay outbox/retry của app.
6. Khi hết pending/conflict, so tập ID và mọi trường nghiệp vụ/isDeleted giữa Room A, Room B và server. Ghi rõ nguồn UI/API/log/read-only DB; nếu không đọc được Room thì nêu giới hạn thay vì suy ra hoàn toàn hội tụ từ ảnh.

Không chọn bản thắng bằng giờ thiết bị, không tạo endpoint giải quyết conflict mới, không sửa DB trực tiếp thành kết quả được gắn nhãn thao tác người dùng. Mọi lượt cần expected/actual/pass/fail/chưa chạy và cleanup đúng tài nguyên đã tạo. Bước 12 chưa có số đo; duration của demo không được dùng thay benchmark D7.

## Demo CRUD, conflict và receipt bằng HTTP trên host

Dùng chuỗi [cURL với database tạm](../../server/README.md#tái-hiện-chuỗi-ghi-bằng-curl-với-database-tạm) khi cần trình bày toàn bộ sáu endpoint. Chuỗi đó dùng DB **rỗng chỉ migrate**, cổng 3001 trống và task `612954cb-842b-4d60-a0c8-48a7cc401814`; không seed vì ID này trùng một task seed. UUID/key đã cho cố định trong chuỗi, không chạy lẫn với DB đã có dữ liệu.

| Mốc cần theo dõi | Kết quả mong đợi |
|---|---|
| POST key `7fe7b179-6677-43ec-99e2-c0c035a184a8` | 201/task version 1 |
| PUT key `345ccf57-5bf5-43d7-9175-45375211bbaa`, rồi PUT mới với baseVersion cũ | 200/version 2, sau đó 409/currentTask đầy đủ |
| DELETE key `4099f1b3-084d-41ad-96f8-736621763d0d`, baseVersion 2 | 200/tombstone version 3; GET vẫn trả task đó |
| Retry POST/DELETE cũ | Status/body snapshot cũ; không tăng version hoặc thêm receipt |
| DELETE key mới đuôi `004`, baseVersion 3 | No-op: task/version/time nguyên, có receipt mới |
| PUT phục hồi key đuôi `005`, rồi retry DELETE no-op | Task hiện tại sống version 4; retry vẫn trả tombstone version 3 từ receipt |

Quan sát `mutation.completed` với outcome applied/replay/accepted_noop và requestId khác nhau, mutationId giữ nguyên khi retry. Sau Ctrl+C server của terminal đó, lệnh inspect/SQL read-only ở README kiểm một task version 4, năm receipt và integrity=ok. `response_body` receipt là snapshot thành công, không suy ra từ task hiện tại. Dọn đúng thư mục tạm theo lệnh README, không xóa riêng receipt/tombstone. Đây là demo backend, không phải hai thiết bị Android; không gọi hai terminal cURL là A/B.

Lượt bàn giao Bước 13 còn kiểm tra API sau seed trên DB mới với UUID riêng (khác seed), ghi trong test-results. Để người xem tự tái hiện lỗi mất response một cách có kiểm soát, dùng `test:faults` phía trên thay cho dừng ngẫu nhiên server dùng chung. Kết thúc khi tool exit 0 và bốn cờ cleanup đều true. Không hứa WorkManager chạy tức thì hoặc đồng bộ được khi Android bị force stop; tính năng đó cần A hoàn thiện và thử theo bảo đảm hệ thống.
