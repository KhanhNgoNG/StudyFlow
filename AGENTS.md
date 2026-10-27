# StudyFlow

## Mục đích và phạm vi

StudyFlow là ứng dụng Android quản lý công việc học tập cho bài giữa kỳ môn lập trình mobile. Trọng tâm là hoạt động offline, đồng bộ hai chiều và tính đúng đắn khi gửi lại hoặc xử lý xung đột.

- Không có tài khoản; hai thiết bị sử dụng cùng bộ dữ liệu server.
- Giao diện tiếng Việt, nền sáng, màu chủ đạo tím indigo `#6355D9`.
- Người dùng chọn toàn bộ phiên bản khi có xung đột thực sự; chưa hợp nhất từng trường.
- Chưa có cập nhật thời gian thực, đồng bộ tăng dần, đính kèm, nhóm hoặc lịch sử khôi phục.
- Thực hiện đúng giai đoạn trong yêu cầu hiện tại. Không tự triển khai các giai đoạn sau hoặc thêm tính năng vì tài liệu có mô tả chúng.

## Trước khi thay đổi

1. Đọc hướng dẫn trong phạm vi thư mục đang sửa và kiểm tra trạng thái Git.
2. Đọc `contracts/sync-protocol.md` khi thay đổi dữ liệu, API hoặc đồng bộ. Đây là hợp đồng chung Android và backend.
3. Kiểm tra mã nguồn, cấu hình và tài liệu đã có trước khi tạo project hoặc thêm dependency.
4. Nếu có `docs/design/`, xem đặc tả và ảnh liên quan. Đặc tả hành vi và quyết định của người dùng ưu tiên hơn chi tiết minh họa trong ảnh concept.
5. Giữ nguyên thay đổi của người khác. Không tạo project lồng, reset hoặc ghi đè phần không thuộc nhiệm vụ.

## Cấu trúc repo

- `android/`: project Android Studio; một module `app`.
- `server/`: backend và kiểm thử server.
- `contracts/`: giao thức đồng bộ, OpenAPI và JSON mẫu.
- `docs/`: yêu cầu, thiết kế, kiểm thử, báo cáo và hướng dẫn demo.
- `releases/README.md`: liên kết sản phẩm nộp.
- Không tạo thư mục `prompts/`. Không tạo file rỗng chỉ để mô phỏng toàn bộ cây thư mục.

Chỉ tạo hoặc sửa phần cần thiết cho nhiệm vụ. Thư mục chưa tồn tại không có nghĩa phải triển khai ngay.

## Quy tắc Android

- Kotlin, Jetpack Compose, Material 3; package/applicationId `com.studyflow`; minSdk đề xuất 26.
- Dùng Gradle Kotlin DSL, Gradle Wrapper và version catalog khi khởi tạo mới; giữ cấu hình tương thích nếu project đã tồn tại.
- Một module `app`, UI chia theo tính năng; không chuyển sang Kotlin Multiplatform.
- ViewModel giữ trạng thái màn hình và expose StateFlow bất biến; Compose thu thập state theo lifecycle.
- State trình bày như scroll, focus hoặc trạng thái mở menu đặt ở UI khi phù hợp. Không đưa mọi state vào ViewModel.
- Tách composable hiển thị khỏi logic dữ liệu; composable không trực tiếp gọi Retrofit hoặc DAO.
- Dùng string resources cho chữ trên giao diện. Dùng MaterialTheme cho màu, typography và shape; giữ màu thương hiệu, không tự bật dynamic color thay đổi concept.
- Bảo đảm vùng chạm, nhãn trạng thái bằng chữ, khả năng đọc và nội dung không bị thanh hệ thống hoặc bàn phím che.
- Chọn dependency ổn định và tương thích với môi trường; không nâng phiên bản hoặc thêm framework DI chỉ vì skill đề xuất.
- Chỉ thêm Room, Retrofit, WorkManager khi bước triển khai tương ứng yêu cầu.
- Giai đoạn dữ liệu mẫu: không giả vờ có lưu bền vững hoặc đồng bộ thật; ghi rõ giới hạn trong README.

## Bất biến offline và đồng bộ

Các quy tắc này áp dụng khi triển khai phần dữ liệu và đồng bộ; không yêu cầu xây ngay trong giai đoạn giao diện.

- Room là nguồn dữ liệu cho UI. Thao tác người dùng lưu cục bộ trước, không chờ API mới cập nhật giao diện.
- Lưu task và trạng thái chờ đồng bộ liên quan trong cùng transaction.
- UUID công việc ổn định; phiên bản do server quản lý. Không dùng giờ thiết bị để chọn phiên bản thắng.
- Outbox và snapshot request được lưu bền vững trước khi gửi. Retry giữ nguyên mutationId và nội dung; không tạo ID mới chỉ vì timeout.
- Response chỉ xác nhận snapshot đã gửi. Nếu người dùng sửa tiếp, không ghi đè nội dung mới hoặc đánh dấu sạch thay đổi mới.
- Không ghi đè công việc pending hoặc xung đột khi tải dữ liệu server mà chưa đối chiếu.
- Xóa logic bằng tombstone; không coi bản ghi vắng mặt trong danh sách là xác nhận đã xóa.
- Một phía thay đổi, phía kia không sửa: nhận dữ liệu tự động, không hiện xung đột. Hai phía cùng nội dung: kiểm tra hội tụ thay vì bắt chọn không cần thiết.
- Giữ hai snapshot khi có xung đột; kiểm tra lại version khi xác nhận lựa chọn để tránh ghi đè cập nhật mới trên server.
- Đồng bộ tự động và thủ công dùng chung scheduler và SyncEngine; không chạy các luồng gửi cạnh tranh.
- WorkManager dùng điều kiện mạng, retry/backoff phù hợp và cơ chế tránh bỏ sót thay đổi khi worker kết thúc. Không hứa chạy tức thì hoặc vượt bảo đảm force stop của Android.
- Lỗi tạm thời giữ dữ liệu để retry; lỗi validation cần sửa và xung đột không retry vô hạn.

## Hợp tác với backend

- Khi làm Android, dùng fake API đúng hợp đồng nếu server chưa sẵn sàng.
- Không tự sửa tên trường, enum, endpoint hoặc quy tắc phiên bản riêng ở một phía.
- Nếu nhiệm vụ yêu cầu đổi hợp đồng, cập nhật tài liệu cùng ví dụ và các nơi liên quan; báo rõ ảnh hưởng cho phía còn lại.
- Backend kiểm tra receipt trước version khi nhận retry; ghi công việc và receipt thành công trong cùng transaction.

## Sử dụng skill

- Nếu có, dùng `compose`, `android-ux`, `kotlin-coroutines` khi đúng nhiệm vụ.
- Quyết định StudyFlow và yêu cầu người dùng ưu tiên hơn mặc định của skill; không thêm tính năng hoặc đổi kiến trúc để tuân theo ví dụ chung.
- Skill dùng làm hướng dẫn; đối chiếu API với phiên bản dependency thực tế. Không sao chép API experimental hoặc từ nhánh mới nhất nếu project chưa hỗ trợ.
- Các tên dạng `android-skills:...` trong skill cộng đồng là tham chiếu của bộ nguồn. Nếu skill đó chưa có, dùng tài liệu Android/Kotlin chính thức phù hợp; không tự cài cả bộ bổ sung hoặc công cụ toàn cục.

## Kiểm tra và bàn giao

- Build bằng Wrapper trong `android/`: Windows dùng `./gradlew.bat :app:assembleDebug`; macOS/Linux dùng `./gradlew :app:assembleDebug`.
- Khi thay đổi logic có test phù hợp, chạy `:app:testDebugUnitTest`. Với test thiết bị đã cấu hình và emulator sẵn sàng, chạy `:app:connectedDebugAndroidTest`.
- Chọn kiểm tra theo rủi ro. Ưu tiên ca lọc/sắp xếp, transaction Room, retry, xung đột và sửa trong lúc gửi; không viết test chỉ lặp lại implementation hoặc bắt buộc test trước mọi chỉnh sửa giao diện.
- Nếu có emulator, chạy và kiểm tra các màn hình đã thay đổi. Không tuyên bố đã chạy app hoặc test nếu chưa thực hiện.
- Thiếu SDK, JDK, dependency hoặc emulator: nêu chính xác phần bị chặn; tiếp tục việc độc lập còn làm được. Không tự tải bộ công cụ lớn ngoài phạm vi yêu cầu.
- Cập nhật README tương ứng với cách chạy và giới hạn thực tế. Không ghi kết quả pass, số đo hoặc bằng chứng giả.
- Không commit `.env`, `local.properties`, build output, `node_modules`, database runtime hoặc log cá nhân. Commit Wrapper, lockfile, migration, seed và Room schemas khi đã tạo.
- Không commit hoặc push nếu người dùng chưa yêu cầu.
- Báo cáo ngắn bằng tiếng Việt: đã làm gì, đã kiểm tra gì, kết quả và phần bị chặn. Khi trao đổi quy trình, phân biệt phần đã chạy thật với phần mới thiết kế.
