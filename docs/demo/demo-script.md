# Kịch bản demo

## Demo lỗi và retry backend độc lập — Bước 10

Chạy trong terminal với Node theo engines của server; máy mới chạy `npm ci` trước. Không cần Android, `.env` hoặc database demo. Mỗi lượt công cụ tự tạo file SQLite tạm, chạy migration thật, mở backend/proxy trên port động và chỉ quản lý tài nguyên của chính lượt đó.

```bash
cd /Users/tuankhong/Documents/StudyFlow/server
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
