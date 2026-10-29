# StudyFlow — Đặc tả giao diện

Ngày cập nhật: 28/10/2026.

Tài liệu này và [ảnh concept StudyFlow](mockups/studyflow-concept.png) là nguồn tham chiếu giao diện chung cho các chat code. Ảnh là bản concept đã trao đổi với người dùng và được nhúng trong tài liệu Word của dự án, không phải ảnh chụp ứng dụng đã triển khai.

## 1. Cách sử dụng tài liệu và ảnh

Trước khi tạo hoặc sửa UI:

1. Đọc đặc tả màn hình liên quan trong tài liệu này.
2. Mở xem trực tiếp `docs/design/mockups/studyflow-concept.png`; không chỉ dựa vào tên file hoặc mô tả.
3. Kiểm tra implementation hiện có và giới hạn giai đoạn trong yêu cầu hiện tại.
4. Bám sát bố cục, thứ bậc thông tin, màu sắc và thành phần trong ảnh ở màn hình tương ứng. Áp dụng kích thước thích ứng thay vì sao chép tọa độ pixel của khung điện thoại.
5. Nếu đặc tả hành vi hoặc quyết định mới của người dùng khác ảnh, ưu tiên yêu cầu người dùng và đặc tả bằng chữ. Giữ nguyên các chức năng đã hoạt động khi điều chỉnh bố cục.

Ảnh có bốn màn hình từ trái sang phải:

| Vị trí | Màn hình tham chiếu | Phạm vi áp dụng |
|---|---|---|
| 01 | Trang chủ | Header, thẻ tiến độ, danh sách sắp đến hạn, thanh tab |
| 02 | Công việc | Ô tìm kiếm, chip trạng thái, thẻ task, nhãn đồng bộ và FAB |
| 03 | Thêm công việc | Cấu trúc biểu mẫu; tái sử dụng khi sửa |
| 04 | Xung đột | Giải thích, hai phiên bản và hành động xác nhận |

Màn hình Chi tiết có ảnh riêng [05 — Chi tiết công việc](mockups/studyflow-task-detail.png). Mở ảnh này khi làm màn hình Chi tiết. Màn hình Đồng bộ chưa có ảnh concept riêng; thiết kế theo quy tắc chung, không coi bố cục tự dựng là mẫu đã được người dùng duyệt.

## 2. Phong cách và thành phần dùng chung

| Thành phần | Quy tắc |
|---|---|
| Công nghệ | Jetpack Compose, Material 3, giao diện tiếng Việt |
| Màu chính | Tím indigo `#6355D9` |
| Nền | Trắng ngà/sáng `#FAFAFC`; bề mặt thẻ sáng |
| Chữ chính | `#17172B`; chữ phụ dùng màu dịu nhưng đủ tương phản |
| Môn học | Chip màu pastel; ánh xạ mã môn thống nhất với model hiện có |
| Trạng thái | Xanh lá cho đã đồng bộ, hổ phách cho chờ/ngoại tuyến, đỏ cho lỗi; luôn có nhãn chữ |
| Khoảng cách | Lề nội dung ngang 16 dp, nhịp khoảng cách cơ sở 8 dp |
| Hình dạng | Thẻ bo góc vừa phải khoảng 12–16 dp; các màn hình dùng cùng theme |
| Chữ | Typography hệ thống theo MaterialTheme; hỗ trợ tăng cỡ chữ |
| Tương tác | Vùng chạm tối thiểu 48 dp; phản hồi rõ khi bấm hoặc lưu |

- Định nghĩa màu, typography và shape tập trung trong theme. Không rải màu cứng vào từng màn hình.
- Giữ màu thương hiệu; không tự bật dynamic color hoặc thêm dark mode trong phạm vi hiện tại.
- Dùng string resources cho nội dung UI; không hardcode tên người dùng hoặc ngày trong ảnh.
- Các icon hành động cần mô tả tiếp cận; icon trang trí không đọc lặp lại nhãn.
- Không chỉ dùng màu để thể hiện chọn phiên bản, trạng thái hoặc quá hạn.
- Nội dung cuộn được khi dài; system bar, bàn phím, thanh tab và FAB không che nội dung.
- Không đưa khung điện thoại hoặc số thứ tự màn hình trong ảnh vào giao diện app.

## 3. Điều hướng chung

Ba tab chính: **Trang chủ — Công việc — Đồng bộ**. Tab hiện tại có icon và nhãn được thể hiện rõ.

- Bấm task mở Chi tiết theo ID ổn định.
- Bấm FAB tại danh sách mở Thêm công việc.
- Sửa từ Chi tiết dùng chung biểu mẫu với Thêm.
- Biểu mẫu và Chi tiết có nút quay lại; ưu tiên ẩn thanh tab ở các màn hình này để tránh luồng thoát không rõ ràng.
- Quay lại phải về đúng nơi mở; không nhân bản màn hình trong back stack.
- Không làm mất truy vấn, bộ lọc hoặc vị trí cuộn chỉ vì chuyển tab/đi chi tiết.
- Biểu mẫu có thay đổi chưa lưu cần xác nhận khi thoát.

## 4. Trang chủ

Bám bố cục màn hình 01 trong ảnh:

1. Header StudyFlow, trạng thái đồng bộ phù hợp giai đoạn.
2. Lời chào chung như “Chào bạn!” và ngày hiện tại.
3. Thẻ tím “Tiến độ hôm nay”, số đã hoàn thành/tổng và thanh tiến độ.
4. Nhóm công việc quá hạn khi có; nhóm sắp đến hạn cùng “Xem tất cả”.
5. Thanh điều hướng dưới.

Quy tắc dữ liệu khi đã có Room:

- Tiến độ hôm nay chỉ tính task chưa xóa có deadline hôm nay, bao gồm cả task đã hoàn thành.
- Khi tổng bằng 0, dùng thông điệp không có việc hôm nay; không chia cho 0 hoặc gán 100%.
- Quá hạn: chưa hoàn thành, chưa xóa và deadline trước hôm nay.
- Sắp đến hạn: chưa hoàn thành, chưa xóa và có deadline từ hôm nay trở đi.
- Mỗi nhóm hiển thị tối đa 3 task; deadline tăng dần, sau đó ưu tiên cao trước rồi ID ổn định.
- Bấm task mở Chi tiết hiện có; “Xem tất cả” chuyển sang danh sách với điều kiện rõ ràng, có thể xóa bộ lọc.
- Số liệu phải cập nhật theo dữ liệu chung, không có task mẫu hoặc danh sách mutable riêng để minh họa số liệu thật.
- Hiển thị trạng thái rỗng và lỗi đọc phù hợp.

## 5. Công việc

Bám màn hình 02 trong ảnh:

- Tiêu đề “Công việc”.
- Ô “Tìm công việc”, có hành động xóa truy vấn.
- Chip “Tất cả”, “Chưa xong”, “Hoàn thành”.
- Banner ngoại tuyến/chờ gửi chỉ thể hiện dữ liệu trạng thái thực hoặc ghi rõ đang minh họa trong demo.
- Danh sách TaskCard và FAB dấu cộng.
- Thanh tab, với Công việc được chọn.

TaskCard gồm checkbox, tiêu đề, môn học, deadline và nhãn trạng thái. Có thể hiển thị ưu tiên/quá hạn theo implementation hiện tại; không nhồi thêm các nhãn lặp thông tin làm thẻ khó đọc.

- Deadline trống dùng “Chưa có hạn”.
- Hoàn thành có cách thể hiện rõ nhưng vẫn đọc được nội dung.
- Checkbox không đồng thời kích hoạt hành động mở Chi tiết.
- Task đã xóa không xuất hiện trong danh sách đang hoạt động.
- LazyColumn dùng key ổn định theo ID.
- Không gỡ chức năng đã hoàn thiện chỉ để khớp từng chi tiết minh họa của ảnh.

## 6. Thêm và sửa công việc

Bám màn hình 03 trong ảnh, dùng cùng một biểu mẫu:

1. Header với nút quay lại và tiêu đề Thêm/Sửa công việc.
2. Tên công việc.
3. Mô tả nhiều dòng.
4. Chọn môn học.
5. Chọn hạn hoàn thành và có cách bỏ hạn.
6. Nhóm chọn ưu tiên Thấp/Vừa/Cao.
7. Nút Lưu công việc nổi bật, đủ chiều rộng trong vùng nội dung.
8. Thông tin lưu trên thiết bị phù hợp giai đoạn.

- Tên sau trim bắt buộc 1–120 ký tự; mô tả tối đa 2.000 ký tự.
- Deadline là ngày, có thể để trống; cho phép quá khứ và biểu thị quá hạn ở nơi hiển thị task.
- Lỗi hiển thị sát trường; lỗi lưu giữ lại bản nháp.
- Khi sửa, điền dữ liệu hiện tại và giữ ID cùng trạng thái hoàn thành ngoài biểu mẫu.
- Không cập nhật task thật mỗi lần nhập; chỉ ghi khi Lưu thành công.
- Khi đã dùng Room, chỉ điều hướng sau transaction thành công.
- Không báo đã đồng bộ server khi mới lưu cục bộ.

## 7. Chi tiết công việc

Bám ảnh [05 — Chi tiết công việc](mockups/studyflow-task-detail.png): header quay lại, nhãn chờ đồng bộ, tiêu đề nổi bật, chip môn học/trạng thái, mô tả, thẻ deadline/ưu tiên, thời điểm cập nhật và nhóm hành động ở dưới. Ẩn thanh tab ở màn hình này. Icon sửa trên header và nút Sửa cùng dẫn tới một biểu mẫu; không tạo hai hành vi khác nhau. Ngày, giờ, tiêu đề trong ảnh là dữ liệu minh họa, phải lấy dữ liệu hiện tại khi triển khai.

- Tiêu đề, mô tả đầy đủ, môn học, deadline, ưu tiên, hoàn thành và nhãn đồng bộ.
- Các hành động Sửa, Hoàn thành/Mở lại, Xóa công việc.
- Xóa phải xác nhận; sau thành công không để quay lại màn hình task vừa xóa.
- Nội dung dài có thể cuộn; task thiếu/đã xóa có thông báo và lối về danh sách.
- Đọc nội dung theo ID từ nguồn chung để phản ánh thay đổi, không giữ snapshot tách rời lâu dài.

## 8. Đồng bộ

Chưa có ảnh riêng. Ưu tiên bố cục gọn: trạng thái chung, số việc chờ, lần đồng bộ gần nhất, nút Đồng bộ ngay và các mục cần xử lý.

Trước khi có SyncEngine/server:

- Ghi rõ “Chưa kết nối máy chủ”.
- Hiển thị “Lần đồng bộ gần nhất: Chưa có”.
- Số công việc chờ lấy từ metadata Room nếu đã có, gồm tombstone đang pending; không gọi đó là số thao tác khi chưa có outbox.
- Nút Đồng bộ ngay bị vô hiệu hóa kèm lý do.
- Không tạo kết quả thành công, timestamp hoặc xung đột giả trong luồng chính.

Khi giai đoạn đồng bộ được yêu cầu và triển khai:

- Thể hiện chờ, đang chạy, thành công, thất bại và cần xử lý theo dữ liệu thật.
- Có mạng nhưng không có pending vẫn có thể yêu cầu tải thay đổi server.
- Không có mạng hoặc đang chạy thì nút có trạng thái phù hợp.
- Không ghi “Đã đồng bộ tất cả” khi còn pending, lỗi hoặc xung đột.

## 9. Xử lý xung đột

Bám màn hình 04 trong ảnh:

- Header có nút quay lại.
- Giải thích ngắn rằng cùng một công việc đã thay đổi ở hai phía.
- Thông tin task và hai bản “Trên thiết bị này” / “Trên máy chủ”.
- Trình bày đủ các trường cần so sánh, làm nổi bật điểm khác nhau bằng nhãn/định dạng ngoài màu.
- Lựa chọn radio loại trừ nhau; không chọn sẵn.
- “Giữ phiên bản đã chọn” chỉ bật khi đã chọn.
- Cảnh báo chọn toàn bộ một bản sẽ bỏ thay đổi chỉ có ở bản kia.

Biến thể xóa–sửa dùng “Giữ công việc” / “Xác nhận xóa”; hiển thị rõ phía đã xóa.

Trước khi có cơ chế xung đột thật, chỉ dựng composable, Preview hoặc demo debug được gắn nhãn; không sửa task thật hoặc tạo badge xung đột giả. PENDING không đồng nghĩa với xung đột.

Khi tích hợp thật: không tự bật màn hình làm gián đoạn nhập; dùng banner/badge dẫn tới xử lý. Giữ hai snapshot đến khi hoàn tất, kiểm tra lại version lúc xác nhận và cho quay lại khi chưa quyết định.

## 10. Các chi tiết trong ảnh cần điều chỉnh

- Bỏ icon hồ sơ/avatar trên Trang chủ vì không có tài khoản.
- “Chào Khánh!” đổi thành lời chào chung; ngày và deadline trong ảnh chỉ là dữ liệu minh họa.
- Dòng “Tự động lưu trên thiết bị” không phải checkbox để bật/tắt lưu cục bộ. Biểu mẫu vẫn phải bấm Lưu mới ghi task; không diễn đạt như autosave nội dung chưa xác nhận.
- Không chọn sẵn bản cục bộ trong màn hình xung đột, dù ảnh minh họa có radio đang chọn.
- Không hiện “Đã đồng bộ” thật trước khi có phản hồi xác nhận phù hợp; dùng Preview nếu cần xem biến thể trạng thái.
- Nhãn môn học ánh xạ từ mã dùng chung, không hardcode tên rút gọn trong ảnh nếu khác quy ước hiện tại.

## 11. Kiểm tra khi bàn giao UI

- Đã mở xem ảnh và đối chiếu đúng màn hình liên quan.
- Màu, thứ bậc thông tin, thẻ, chip, biểu mẫu và thanh tab nhất quán.
- Chức năng hiện có, dữ liệu Room và điều hướng không bị mất khi chỉnh giao diện.
- Trạng thái trống, lỗi, nội dung dài, chữ lớn và bàn phím được xử lý.
- Preview/demo không bị trình bày như hoạt động đồng bộ thật.
- Chỉ triển khai phạm vi người dùng yêu cầu, không tự hoàn tất cơ chế mạng vì tài liệu có mô tả hình thức tương lai.
- Ghi rõ phần mới kiểm tra bằng Preview và phần đã chạy trên emulator; không tuyên bố đã kiểm tra nếu chưa chạy.
