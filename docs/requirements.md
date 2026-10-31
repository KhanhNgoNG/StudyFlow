# Yêu cầu hệ thống

## Căn cứ và giới hạn

Đặc tả dự án hiện có là [StudyFlow_Dac_ta_va_ke_hoach_du_an.docx](../StudyFlow_Dac_ta_va_ke_hoach_du_an.docx), phần 1, 9, 14 và 17. Tài liệu này tự ghi là thiết kế, không phải bằng chứng đã triển khai; nó dẫn tới đề gốc PDF 6 trang nhưng PDF không có trong repo để kiểm tra độc lập trong Bước 13. Bảng D1–D8 ở [báo cáo](report/outline.md) bám đặc tả và yêu cầu bàn giao của người dùng; cả nhóm cần đối chiếu lại đề gốc khi chốt sản phẩm nộp.

Theo đặc tả, Kotlin, Room, ViewModel, Repository, Retrofit, Coroutines/Flow và WorkManager thuộc yêu cầu đề. Compose/Material 3 và Node.js/Express/SQLite server là **lựa chọn kỹ thuật StudyFlow**, không phải yêu cầu thư viện bắt buộc từ đề. Quyết định nghiệp vụ đã chốt trong [Sync Protocol](../contracts/sync-protocol.md) ưu tiên hơn đề xuất cũ trong DOCX.

## Yêu cầu backend đã thực hiện

| Nhu cầu | Hành vi / căn cứ kiểm chứng |
|---|---|
| Bộ dữ liệu dùng chung, không tài khoản | Không userId/token; sáu endpoint dưới `/api/v1`, [OpenAPI](../contracts/openapi.yaml) |
| Đọc đầy đủ để đồng bộ | Danh sách mặc định includeDeleted=true, false lọc task sống; chi tiết trả cả tombstone; query sai/lặp 422 |
| Tạo và cập nhật có kiểm soát | Client cấp UUID; POST baseVersion=0 tạo version 1; ID đã có trả conflict; PUT thay toàn bộ, không upsert, chỉ ghi khi version khớp |
| Xóa có thể truyền sang thiết bị khác | DELETE 200/envelope task, giữ tombstone; matched tombstone mới là no-op giữ task/version/time nhưng lưu receipt mới |
| Retry an toàn khi mất phản hồi | Idempotency-Key UUID + fingerprint; receipt trước version; replay đúng status/body snapshot cũ, không áp dụng lại; đổi request cùng key trả 409 MUTATION_ID_REUSED |
| Không ghi dở dang | Ghi task và receipt thành công cùng transaction; lỗi callback/INSERT/COMMIT rollback, không ghi receipt lỗi |
| Xung đột rõ ràng | 409 VERSION_CONFLICT có currentTask đầy đủ; timestamp server chỉ là metadata, không dùng giờ thiết bị chọn thắng |
| Validation nhất quán | JSON đúng kiểu, UUID/enum/ngày/null/boolean/Unicode/giới hạn trong hợp đồng; 422 với fieldErrors, lỗi hạ tầng không lộ SQL |
| Có thể chạy lại từ nguồn | Lockfile, `.env.example`, migration/checksum, seed cố định, inspect và test/fault tool độc lập; [README server](../server/README.md) |

## Phần chưa được xác nhận

Offline và lưu Room trước HTTP là nghĩa vụ Android; backend độc lập không chứng minh điều này. Hai snapshot/whole-version choice, hội tụ cùng nội dung, sửa trong lúc gửi, scheduler chung và WorkManager phải được A/cả nhóm ghép thử. Các điểm thiếu và lệch ACK no-op/đếm Unicode ở Android đã ghi trong [setup](setup.md), không sửa hợp đồng để tránh chúng.

Chưa có tài khoản, HTTPS/deploy public, realtime, sync tăng dần, attachment, nhóm, lịch sử khôi phục hoặc garbage collection receipt/tombstone. Những phần này nằm ngoài phạm vi hiện tại. Chưa có benchmark D7, APK/video/báo cáo cuối hoặc bằng chứng hội tụ hai thiết bị; xem [kế hoạch](project-plan.md) và [kết quả thực tế](testing/test-results.md).
