# Bản phát hành

## Kiểm kê ngày 30/10/2026

Chưa có APK/AAB, video hoặc báo cáo cuối PDF/Word được xác minh trong `releases/`, `docs/report/` hay output build hiện tại. Không có liên kết download giả và không tạo/publish/upload release trong Bước 13. Đặc tả DOCX ở gốc là tài liệu thiết kế đã có, không gọi là báo cáo cuối.

| Sản phẩm thực sự có trong repo | Vai trò / bằng chứng |
|---|---|
| [Server và hướng dẫn](../server/README.md) | Mã nguồn, lockfile, migration/seed, sáu API, test và fault tool; chạy local theo README |
| [Sync Protocol](../contracts/sync-protocol.md), [OpenAPI](../contracts/openapi.yaml), [JSON mẫu](../contracts/examples/README.md) | Hợp đồng dùng chung; đã đối chiếu bằng test subset/HTTP thật, chưa có chứng nhận validator đầy đủ |
| [Kết quả kiểm thử](../docs/testing/test-results.md), [ca kiểm thử](../docs/testing/test-cases.md) | Các lượt backend đã chạy và giới hạn; A/B chưa chạy, không có ảnh Android mới trong Bước 13 |
| [Setup](../docs/setup.md), [demo](../docs/demo/demo-script.md) | Tái hiện backend độc lập và điều kiện ghép Android |
| [Nội dung backend trong báo cáo](../docs/report/outline.md) | Markdown để nhóm tổng hợp; bảng D1–D8, nguồn và việc còn thiếu, không phải bản nộp cuối |

Lượt tái hiện Bước 13 bao gồm tài liệu chưa commit ở thời điểm 30/10/2026; [kết quả](../docs/testing/test-results.md) giữ rõ nguồn snapshot đã thử. [README gốc](../README.md) hướng dẫn lấy nhánh và ghi nhận commit thực tế. Commit/push tài liệu không tạo release hay artifact nộp mới. Không đính kèm `.env`, node_modules, DB/WAL/SHM hoặc log runtime vào release mã nguồn.

A cần cung cấp APK đã cài/chạy thực tế. A và B cần hoàn thiện tích hợp hai thiết bị, thu video/ảnh/log phù hợp, đối chiếu đề gốc và tổng hợp báo cáo. B chưa có số đo D7 (`measurements.csv` chỉ header). Chỉ thêm liên kết artifact cuối sau khi file tồn tại, đã kiểm tra được nội dung và nhóm cho phép phát hành.
