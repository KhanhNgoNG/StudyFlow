# StudyFlow — Hợp đồng Android và backend

**Phiên bản:** 1.0

**Ngày:** 27/10/2026

**Trạng thái:** Đã được hai phía Android và backend xác nhận; là hợp đồng chính thức để triển khai phiên bản đầu.

Tài liệu quy định dữ liệu, REST API và hành vi đồng bộ chung của StudyFlow. Phía Android có thể xây dựng API giả theo hợp đồng này trong khi backend phát triển API thật. Các thay đổi hợp đồng cần cập nhật tài liệu và thông báo cho cả hai phía.

## 1. Phạm vi dùng chung

- Không đăng ký hoặc đăng nhập; hai thiết bị sử dụng cùng một bộ dữ liệu trên server.
- Android lưu thay đổi vào Room trước và cập nhật giao diện từ dữ liệu cục bộ.
- Server quản lý phiên bản của từng công việc.
- Chỉ gửi các thay đổi cục bộ đang chờ; tải xuống toàn bộ công việc, bao gồm dấu vết xóa.
- Chỉ yêu cầu người dùng xử lý khi cùng một công việc có các thay đổi độc lập chưa hội tụ ở hai phía.
- Khi xung đột, người dùng chọn toàn bộ một phiên bản. Bản đầu không hợp nhất từng trường.
- Tự động và thủ công dùng chung cơ chế đồng bộ. Không có cập nhật thời gian thực trong bản đầu.

## 2. Cấu trúc công việc trên API

| Trường | Kiểu JSON | Quy định |
|---|---|---|
| `id` | string | UUID do Android tạo, giữ nguyên trên server và mọi thiết bị. |
| `title` | string | Bắt buộc, từ 1 đến 120 ký tự sau khi trim. |
| `description` | string | Tối đa 2.000 ký tự; mặc định chuỗi rỗng. |
| `subjectCode` | string | Một trong `MOBILE`, `DATABASE`, `GENERAL`, `OTHER`. |
| `dueDate` | string hoặc null | Ngày hợp lệ theo `YYYY-MM-DD`, không chứa giờ. |
| `priority` | string | Một trong `LOW`, `MEDIUM`, `HIGH`; mặc định `MEDIUM`. |
| `completed` | boolean | Trạng thái hoàn thành; mặc định `false`. |
| `isDeleted` | boolean | Dấu vết xóa logic; mặc định `false`. |
| `version` | integer | Server quản lý; bản ghi đã tạo bắt đầu từ 1. |
| `serverUpdatedAt` | string | Thời gian UTC theo ISO 8601, do server tạo. |

Các giá trị mặc định được Android đưa rõ vào request. POST và PUT gửi đầy đủ các trường nghiệp vụ, kể cả `dueDate: null` khi không có hạn. `version` và `serverUpdatedAt` chỉ xuất hiện trong dữ liệu trả về; client gửi `baseVersion` để kiểm tra phiên bản khi ghi.

### 2.1. Nhãn môn học trên Android

| Mã | Nhãn hiển thị |
|---|---|
| `MOBILE` | Lập trình mobile |
| `DATABASE` | Cơ sở dữ liệu |
| `GENERAL` | Đại cương |
| `OTHER` | Khác |

### 2.2. Metadata riêng của Android

Trạng thái đồng bộ, lỗi, số lần thử, `localRevision`, outbox và snapshot xung đột là dữ liệu cục bộ. Không gửi chúng như các trường nghiệp vụ của công việc.

- `baseVersion`: phiên bản server mà thay đổi cục bộ đang dựa vào; không cập nhật tùy ý khi tải về một bản mới đang xung đột.
- `localRevision`: số tăng khi người dùng thay đổi công việc, dùng để tránh phản hồi cũ đánh dấu sạch hoặc ghi đè một chỉnh sửa mới.
- `mutationId`: UUID của một thao tác gửi, lưu bền vững trước khi gọi mạng.

## 3. Quy ước HTTP

- Base path: `/api/v1`.
- Request và response sử dụng JSON UTF-8.
- POST, PUT và DELETE bắt buộc có header `Idempotency-Key` chứa `mutationId`.
- POST và PUT có `Content-Type: application/json`.
- Một công việc có `id` ổn định; mỗi thao tác mới có `mutationId` riêng.
- Retry sau timeout giữ nguyên method, đường dẫn, tham số, payload và `mutationId` của thao tác cũ.
- Không dùng giờ thiết bị để quyết định phiên bản nào thắng.

## 4. Danh sách endpoint

| Method | Endpoint | Hành vi |
|---|---|---|
| GET | `/health` | Kiểm tra server sẵn sàng; không thay thế kết quả đồng bộ thực tế. |
| GET | `/tasks?includeDeleted=true` | Lấy toàn bộ công việc và dấu vết xóa. |
| GET | `/tasks/{id}` | Lấy phiên bản hiện tại của công việc, kể cả dấu vết xóa. |
| POST | `/tasks` | Tạo công việc với ID do Android cung cấp, `baseVersion = 0`. |
| PUT | `/tasks/{id}` | Cập nhật toàn bộ nội dung khi `baseVersion` khớp. |
| DELETE | `/tasks/{id}?baseVersion=3` | Xóa logic khi phiên bản hiện tại bằng 3. |

GET danh sách không phân trang trong bản đầu. Server trả dữ liệu từ một snapshot đọc nhất quán. Android luôn dùng `includeDeleted=true` khi đồng bộ.

Quyết định bổ sung được người dùng chốt khi triển khai Bước 6 (29/10/2026):

- Không truyền `includeDeleted` hoặc truyền `includeDeleted=true`: trả cả công việc đang hoạt động và dấu vết xóa.
- `includeDeleted=false`: chỉ trả công việc chưa xóa (`isDeleted=false`).
- Nếu truyền tham số, chỉ chấp nhận một giá trị chuỗi HTTP `true` hoặc `false`; giá trị khác hoặc query bị lặp trả `422 VALIDATION_ERROR` với `fieldErrors.includeDeleted`.
- Android vẫn gửi rõ `includeDeleted=true` khi đồng bộ, không phụ thuộc vào giá trị mặc định.

GET chi tiết trả `404` nếu ID chưa từng tồn tại. Công việc đã xóa logic vẫn trả object với `isDeleted=true`. Không coi việc thiếu một ID trong danh sách là bằng chứng để xóa dữ liệu cục bộ.

## 5. Request và response mẫu

### 5.1. Tạo công việc

```http
POST /api/v1/tasks
Idempotency-Key: 7fe7b179-6677-43ec-99e2-c0c035a184a8
Content-Type: application/json
```

```json
{
  "id": "612954cb-842b-4d60-a0c8-48a7cc401814",
  "baseVersion": 0,
  "title": "Thiết kế giao diện StudyFlow",
  "description": "Hoàn thiện các màn hình chính",
  "subjectCode": "MOBILE",
  "dueDate": "2026-10-28",
  "priority": "MEDIUM",
  "completed": false,
  "isDeleted": false
}
```

Tạo thành công trả `201`:

```json
{
  "task": {
    "id": "612954cb-842b-4d60-a0c8-48a7cc401814",
    "title": "Thiết kế giao diện StudyFlow",
    "description": "Hoàn thiện các màn hình chính",
    "subjectCode": "MOBILE",
    "dueDate": "2026-10-28",
    "priority": "MEDIUM",
    "completed": false,
    "isDeleted": false,
    "version": 1,
    "serverUpdatedAt": "2026-10-27T03:00:00Z"
  }
}
```

POST dùng `isDeleted=false`. Khi cùng ID đã tồn tại nhưng request không phải retry của mutation thành công trước đó, server không tạo bản ghi thứ hai; trả `409 VERSION_CONFLICT` kèm `currentTask` để client đối chiếu.

### 5.2. Cập nhật công việc

```http
PUT /api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814
Idempotency-Key: 345ccf57-5bf5-43d7-9175-45375211bbaa
Content-Type: application/json
```

```json
{
  "id": "612954cb-842b-4d60-a0c8-48a7cc401814",
  "baseVersion": 1,
  "title": "Thiết kế 4 màn hình StudyFlow",
  "description": "Hoàn thiện các màn hình chính",
  "subjectCode": "MOBILE",
  "dueDate": "2026-10-28",
  "priority": "MEDIUM",
  "completed": false,
  "isDeleted": false
}
```

ID trong body phải khớp đường dẫn. Cập nhật thành công trả `200`, dùng envelope `{"task": ...}` với đầy đủ dữ liệu được lưu và version mới. PUT không tự tạo ID chưa tồn tại. Trong bản đầu, xóa dùng DELETE; PUT dùng `isDeleted=false`, bao gồm trường hợp người dùng xác nhận giữ công việc trước một tombstone có version khớp.

### 5.3. Xóa logic

```http
DELETE /api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814?baseVersion=2
Idempotency-Key: 4099f1b3-084d-41ad-96f8-736621763d0d
```

DELETE không cần body. Thành công trả `200` với envelope `{"task": ...}`; task giữ ID và các trường nghiệp vụ, có `isDeleted=true`, version mới và thời gian server mới. Server không xóa vật lý bản ghi trong giai đoạn demo.

### 5.4. Lấy danh sách và chi tiết

GET danh sách trả `200`:

```json
{
  "tasks": []
}
```

Khi có dữ liệu, mỗi phần tử có toàn bộ cấu trúc công việc ở mục 2. GET chi tiết trả `200` với envelope `{"task": ...}` giống response ghi thành công.

### 5.5. Response xung đột

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "code": "VERSION_CONFLICT",
  "message": "Task changed since baseVersion",
  "taskId": "612954cb-842b-4d60-a0c8-48a7cc401814",
  "currentTask": {
    "id": "612954cb-842b-4d60-a0c8-48a7cc401814",
    "title": "Thiết kế 5 màn hình StudyFlow",
    "description": "Bổ sung màn hình đồng bộ",
    "subjectCode": "MOBILE",
    "dueDate": "2026-10-29",
    "priority": "HIGH",
    "completed": false,
    "isDeleted": false,
    "version": 2,
    "serverUpdatedAt": "2026-10-27T03:05:00Z"
  }
}
```

`currentTask` phải đầy đủ, không chỉ gồm các trường đã sửa. Android xử lý dựa trên `code`, không dựa vào câu chữ của `message`.

## 6. Quy tắc phiên bản và phát hiện xung đột

Mỗi thay đổi mới được server chấp nhận làm version công việc tăng một đơn vị. Retry của mutation đã thành công không tăng version lần nữa. Kiểm tra version và ghi dữ liệu phải nguyên tử trong transaction.

Ví dụ A và B cùng có phiên bản 1:

| Tình huống | Kết quả |
|---|---|
| A sửa với `baseVersion=1`, server còn version 1 | Chấp nhận và tạo version 2. |
| B không sửa, tải về version 2 | Cập nhật tự động, không xung đột. |
| B sửa độc lập từ version 1, server đã ở version 2 | Server trả `VERSION_CONFLICT`; Android đối chiếu nội dung. |
| Nội dung B muốn gửi giống toàn bộ nội dung hiện tại trên server | Xác nhận hội tụ sau khi đối chiếu, không bắt người dùng chọn lại. |
| Nội dung B khác server | Giữ hai bản và yêu cầu người dùng chọn. |
| B đã nhận version 2 rồi mới sửa | Gửi dựa trên version 2; không xung đột nếu server chưa đổi tiếp. |
| A và B sửa hai công việc khác nhau | Xử lý riêng từng ID, không xung đột với nhau. |

Đối chiếu hội tụ sử dụng các trường nghiệp vụ cùng `isDeleted`; bỏ qua timestamp và metadata cục bộ. Nếu cả hai đã xóa, coi là cùng ý định xóa. Hai phía sửa các trường khác nhau nhưng cùng công việc vẫn yêu cầu chọn toàn bộ một bản trong phiên bản đầu.

## 7. Giải quyết xung đột

Chính sách: **User-assisted Conflict Resolution**, người dùng chọn toàn bộ phiên bản.

- Android lưu bền vững snapshot cục bộ và snapshot server, không ghi đè trước khi có quyết định.
- Không tự mở màn hình làm gián đoạn nhập liệu; dùng thông báo trong app hoặc badge dẫn tới xử lý.
- Tạm khóa chỉnh sửa công việc đang xung đột; công việc khác vẫn hoạt động.
- Chưa chọn thì giữ nguyên dữ liệu chờ xử lý.

### 7.1. Giữ bản server

Android đọc lại bản server. Nếu server đã thay đổi so với bản được hiển thị, yêu cầu người dùng xem lại. Nếu còn phù hợp, lưu bản được chọn vào Room và bỏ thay đổi cục bộ đang xung đột trong cùng transaction. Lượt đồng bộ sau vẫn tiếp nhận cập nhật mới của server.

### 7.2. Giữ bản thiết bị

Android gửi PUT mới, mutationId mới, với `baseVersion` bằng version server được người dùng so sánh. Chỉ đóng xung đột sau response hợp lệ. Nếu server đã đổi tiếp, giữ bản cục bộ và cập nhật màn hình xung đột bằng bản server mới.

### 7.3. Xung đột giữa xóa và sửa

- **Giữ công việc:** PUT nội dung được chọn với `isDeleted=false` và baseVersion hiện tại; có thể phục hồi một tombstone sau quyết định rõ ràng của người dùng.
- **Xác nhận xóa:** nếu server còn bản sống thì DELETE với baseVersion hiện tại; nếu server đã là tombstone thì kiểm tra lại rồi nhận tombstone đó.
- Không tự phục hồi công việc đã xóa khi tải dữ liệu thông thường.

### 7.4. Giới hạn của chính sách

Chọn toàn bộ một bản sẽ loại bỏ những thay đổi chỉ có ở bản không được chọn. Ví dụ A sửa tiêu đề, B sửa mô tả; chọn A có thể làm mất mô tả mới của B. Bản đầu chưa có lịch sử khôi phục sau xác nhận. Giao diện phải hiển thị rõ các khác biệt và cảnh báo khi xác nhận xóa.

## 8. Idempotency và gửi lại

1. Android lưu mutationId, method, đường dẫn, tham số, payload, baseVersion và localRevision trước khi gửi.
2. Snapshot đã bắt đầu gửi không được sửa. Timeout hoặc mất phản hồi thì gửi lại đúng snapshot với cùng mutationId.
3. Server kiểm tra receipt của mutation trước khi kiểm tra version hiện tại.
4. Nếu mutation đã thành công với cùng nội dung, trả lại status và body thành công đã lưu, không áp dụng lần hai.
5. Nếu cùng mutationId nhưng khác nội dung, trả `409 MUTATION_ID_REUSED`.
6. Nếu là mutation mới, kiểm tra version và validate; ghi thay đổi cùng receipt thành công trong một transaction.
7. Android tạo mutationId mới cho chỉnh sửa mới hoặc quyết định giải quyết xung đột, không tạo ID mới chỉ vì timeout.

Server giữ receipt thành công và tombstone suốt đợt demo/kiểm thử. Khi reset bộ dữ liệu, cần reset có phối hợp server và hai thiết bị. Receipt áp dụng cho thao tác thành công; sau lỗi tạm thời chưa commit, cùng request có thể được thử lại.

## 9. Bảo vệ thay đổi mới trong lúc đồng bộ

Ví dụ Android gửi snapshot localRevision 5, người dùng sửa tiếp thành revision 6 trong lúc chờ:

- Response thành công chỉ xác nhận revision 5.
- Cập nhật baseVersion theo kết quả đã chấp nhận, nhưng không chép nội dung revision 5 đè revision 6.
- Giữ revision 6 ở trạng thái pending và gửi bằng mutation mới sau khi giải quyết xong request trước.
- Chỉ một mutation đang gửi cho mỗi công việc; tuần tự hóa toàn bộ SyncEngine trong bản đầu.
- Khi tải xuống, không ghi đè bản pending hoặc bản xung đột bằng nội dung server mà chưa đối chiếu.
- Ghi task và trạng thái pending/outbox liên quan trong transaction Room.
- Sau khi process chết, tiếp tục mutation đã lưu chưa xác nhận trước khi tạo request mới.

## 10. Mã kết quả và xử lý lỗi

| HTTP và code | Ý nghĩa | Android xử lý |
|---|---|---|
| `200` | Đọc, cập nhật hoặc xóa thành công | Kiểm tra schema và lưu đúng kết quả. |
| `201` | Tạo thành công | Kiểm tra schema và lưu đúng kết quả. |
| `404 TASK_NOT_FOUND` | ID chưa tồn tại | Giữ dữ liệu cục bộ, báo lỗi phù hợp; không tự coi là tombstone. |
| `409 VERSION_CONFLICT` | Phiên bản không khớp | Đối chiếu hội tụ hoặc lưu hai bản xung đột. |
| `409 MUTATION_ID_REUSED` | ID thao tác dùng lại với nội dung khác | Giữ dữ liệu, ghi lỗi; không tự thay ID để che lỗi. |
| `422 VALIDATION_ERROR` | Dữ liệu không hợp lệ | Hiển thị lỗi cần sửa; không retry vô hạn. |
| `429` | Giới hạn request | Giữ thay đổi; tôn trọng Retry-After nếu có. |
| `5xx`, timeout, mất mạng | Lỗi tạm thời | Giữ mutation, retry với backoff phù hợp. |

Response 2xx thiếu hoặc sai dữ liệu không đủ để đánh dấu đã đồng bộ. Với request ghi chưa xác nhận được kết quả, giữ nguyên mutation để thử lại an toàn.

Error body thông thường:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Invalid task data",
  "taskId": "612954cb-842b-4d60-a0c8-48a7cc401814",
  "fieldErrors": {
    "title": "Title is required"
  }
}
```

`taskId` chỉ có khi xác định được; `fieldErrors` dành cho validation; `currentTask` dành cho VERSION_CONFLICT. Thông báo tiếng Việt trên UI được Android ánh xạ từ code và tên trường.

## 11. Điểm kích hoạt và trạng thái đồng bộ

- Sau thay đổi cục bộ: đăng ký tác vụ WorkManager có điều kiện mạng.
- Khi mở app: yêu cầu một lượt đồng bộ để gửi pending và nhận thay đổi server.
- Nút Đồng bộ ngay: sử dụng cùng scheduler/SyncEngine, vẫn có ích khi không có pending để tải thay đổi server.
- Khi không có mạng: nút bị khóa và hiển thị chờ kết nối.
- Khi đang chạy: không tạo luồng đồng bộ cạnh tranh.
- Khi có xung đột: chỉ dừng gửi công việc liên quan; các công việc khác vẫn tiếp tục.
- Không hiển thị Đã đồng bộ tất cả khi vẫn còn pending, lỗi hoặc xung đột.
- WorkManager phụ thuộc lịch của Android; không bảo đảm chạy tức thì hoặc chạy tiếp ngay sau force stop.
- Bản đầu không bảo đảm thiết bị đang mở tự nhận ngay mọi thay đổi từ thiết bị khác; nhận khi có lượt đồng bộ tiếp theo.

## 12. Bộ kiểm tra hợp đồng tối thiểu

- [ ] Tạo công việc trên A, server nhận và B tải về cùng ID.
- [ ] CRUD offline tồn tại sau khi đóng và mở lại app.
- [ ] Chỉ A sửa, B không sửa: B nhận bản mới không có xung đột.
- [ ] A/B sửa khác nhau trên cùng bản gốc: giữ hai bản và xử lý theo lựa chọn.
- [ ] Hai phía có cùng nội dung: xác nhận hội tụ không hỏi lại.
- [ ] Xóa offline được truyền bằng tombstone và không tự xuất hiện lại.
- [ ] Gửi lại mutation đã thành công không tăng version hoặc tạo bản ghi lần nữa.
- [ ] Cùng mutationId khác nội dung bị từ chối.
- [ ] Server commit nhưng mất phản hồi: retry nhận đúng kết quả cũ.
- [ ] Người dùng sửa tiếp trong lúc gửi: phản hồi cũ không làm mất sửa mới.
- [ ] Server đổi trong lúc xem xung đột: quyết định cũ không ghi đè bản mới.
- [ ] Lỗi 422 không gây retry vô hạn; lỗi tạm thời không làm mất dữ liệu.

## 13. Trách nhiệm hai phía

**Android:** Room, UI và ViewModel; outbox bền vững; Retrofit; SyncEngine; WorkManager; hiển thị trạng thái; lưu và xử lý snapshot xung đột; kiểm thử offline và chỉnh sửa trong lúc gửi.

**Backend:** REST API; validation; database và tombstone; kiểm tra version nguyên tử; receipt chống áp dụng trùng; response lỗi đúng hợp đồng; seed và integration test.

**Cùng thực hiện:** xác nhận hợp đồng, tạo JSON mẫu, ghép thử sớm trên hai thiết bị, chạy các kịch bản của đề và ghi nhận số đo thực tế. Cùng hợp đồng đã được mô tả bằng `contracts/openapi.yaml`; giữ hai tài liệu và các JSON mẫu nhất quán khi cập nhật.
