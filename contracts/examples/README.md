# JSON mẫu của StudyFlow

Các file tuân theo `../sync-protocol.md`, phục vụ fake API, tài liệu và fixture kiểm thử. Đây là dữ liệu minh họa, không phải kết quả gọi API thật.

| File | Cách sử dụng |
|---|---|
| `create-task-request.json` | Body POST `/api/v1/tasks`; `baseVersion=0`. |
| `update-task-request.json` | Body PUT `/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814`; dựa trên version 1. |
| `task.json` | Object task đầy đủ ở version 1; không có envelope. |
| `task-response.json` | Body response POST thành công (201), hoặc GET chi tiết ở version 1 (200). |
| `update-task-response.json` | Body response PUT thành công (200), version 2. |
| `tombstone.json` | Object task đã xóa ở version 3; không có envelope. |
| `delete-task-response.json` | Body response DELETE thành công (200), có envelope `task`. |
| `task-list.json` | Body GET danh sách (200), gồm task đang hoạt động và tombstone của một ID khác. |
| `empty-task-list.json` | Body GET danh sách rỗng (200). |
| `conflict-response.json` | Lỗi 409 `VERSION_CONFLICT`, kèm đầy đủ `currentTask`. |
| `error-response.json` | Lỗi 422 `VALIDATION_ERROR`; minh họa request có title không hợp lệ. |
| `task-not-found-response.json` | Lỗi 404 cho ID chưa tồn tại. |
| `mutation-id-reused-response.json` | Lỗi 409 khi cùng mutation ID nhưng request khác. |

## Luồng thành công minh họa

1. POST với `create-task-request.json`, nhận `task-response.json` ở version 1.
2. PUT với `update-task-request.json`, nhận `update-task-response.json` ở version 2.
3. DELETE cùng ID với query `baseVersion=2`, không có body; nhận `delete-task-response.json` ở version 3.

`conflict-response.json` là một nhánh khác: thiết bị khác đã sửa task thành version 2 trước khi PUT được chấp nhận. Không ghép nhánh này vào luồng thành công phía trên.

POST, PUT và DELETE cần header `Idempotency-Key` chứa UUID mutation; POST và PUT có `Content-Type: application/json`. Header không nằm trong JSON body. Mỗi thao tác mới dùng mutation ID riêng; retry giữ nguyên key và toàn bộ request. Retry thành công trả lại status/body đã lưu, không tăng version.

Các file task và tombstone thuần là object để tái sử dụng. Khi giả lập HTTP response chi tiết hoặc ghi thành công, dùng file có envelope `{"task": ...}`. Danh sách đồng bộ dùng `includeDeleted=true`; tombstone trong danh sách có ID khác task sống để không lặp cùng ID trong một snapshot.

Quyết định Bước 6 ngày 29/10/2026: GET danh sách không truyền includeDeleted có cùng hành vi với true, trả cả task sống và tombstone; false chỉ trả task sống. `task-list.json` minh họa kết quả của true hoặc khi không truyền tham số. Giá trị sai hoặc query includeDeleted lặp trả 422 VALIDATION_ERROR với fieldErrors.includeDeleted; cấu trúc lỗi dùng cùng schema như `error-response.json`. Android vẫn gửi rõ includeDeleted=true khi đồng bộ.

Quyết định Bước 8 ngày 29/10/2026: DELETE bằng mutation mới trên tombstone có baseVersion khớp trả 200 với tombstone nguyên trạng và ghi receipt mới; không tăng version/đổi serverUpdatedAt. `delete-task-response.json` cũng có thể là snapshot cho trường hợp task đã ở tombstone version 3 trước request này. Nếu task được phục hồi/sửa tiếp, retry vẫn trả snapshot tombstone đã lưu và không xóa lại. Version cũ vẫn trả VERSION_CONFLICT với currentTask đầy đủ; ID chưa tồn tại trả TASK_NOT_FOUND.

Không tạo JSON request cho DELETE vì hợp đồng không yêu cầu body. Không thêm response health hoặc mã lỗi khác khi chưa có cấu trúc JSON được hợp đồng quy định.
