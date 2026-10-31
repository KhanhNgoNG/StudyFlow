# Kế hoạch dự án

## Trạng thái backend ngày 30/10/2026

Đây là bảng đối chiếu mã/bằng chứng, không đánh dấu hoàn tất theo tên bước. Nhiệm vụ Bước 13 chỉ hoàn thiện tài liệu và tái hiện, không tự làm bù tính năng hoặc benchmark.

| Mốc | Đã có | Giới hạn / việc còn lại |
|---|---|---|
| Bước 4–6 | Schema/migration/seed, validation/mapper/lỗi, health và hai GET; tài liệu lịch sử `backend-step-4/5/6.md` | Hợp đồng hiện hành ở `contracts/`, ưu tiên hơn mô tả mốc cũ |
| Bước 7–8 | Executor/fingerprint/receipt/transaction; POST/PUT/DELETE/version/tombstone/no-op, test và hợp đồng | Đã nằm trong lịch sử Git; không cần triển khai lại |
| Bước 9 | Integration HTTP, restart/persistence, OpenAPI subset và ba race Worker | Commit `c06732e`; 147 test ở mốc đó, không có validator OpenAPI chuyên dụng |
| Bước 10 | Log request/mutation an toàn, năm scenario lỗi và cleanup | Commit `da465da`; tổng 155 test, không benchmark/stress/mất điện |
| Bước 11 | Chuẩn bị bàn giao; backend host 155 test, smoke 11, 15 HTTP request | Tài liệu Bước 11 chưa commit tại lúc bắt đầu Bước 13, được giữ trong gói bàn giao. Chưa ghép Android thật/hai thiết bị; ma trận IT vẫn chưa chạy |
| Bước 12 | Có sẵn header [measurements.csv](testing/measurements.csv) | **Chưa có dòng số đo**, không có kết quả thực nghiệm D7 hoặc kết luận hiệu năng |
| Bước 13 | Hoàn thiện Markdown hiện có, đối chiếu D1–D8, chạy lại trong bản sao có kiểm soát | Chi tiết lượt thực hiện ở [test-results](testing/test-results.md); không tạo release/Word/PDF, không commit/push |

## Bàn giao theo vai trò

- B: cung cấp mã server, lockfile, contract/JSON mẫu, migration/seed, test/fault runner, hướng dẫn và kết quả backend. Chưa có số đo D7; cần thống nhất môi trường/dataset/chỉ số với cả nhóm trước một nhiệm vụ đo riêng.
- A: hoàn thiện adapter HTTP/base URL/quyền mạng debug, binding Application/manual sync, scheduler/worker/resolver; đối chiếu DELETE no-op ACK và Unicode với backend. README Android do A quản lý, không viết lại trong Bước 13.
- A và B: chạy các ca [IT](testing/test-cases.md), đọc Room A/B cùng server, thu bằng chứng thật, xác nhận whole-version choice và stale confirmation. Sau đó mới đánh giá hội tụ, build APK nộp, quay video và tổng hợp báo cáo cuối theo đề gốc.

Thứ tự nhận bàn giao: [README server](../server/README.md) → [kiến trúc](architecture.md)/[database](database-design.md) → [Sync Protocol](../contracts/sync-protocol.md)/[OpenAPI](../contracts/openapi.yaml) → [setup](setup.md) → [demo](demo/demo-script.md). [Báo cáo](report/outline.md) ánh xạ sản phẩm D1–D8 tới mã, tài liệu và phần chưa đủ bằng chứng.
