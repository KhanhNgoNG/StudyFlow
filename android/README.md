# StudyFlow Android — bước 5

Ứng dụng Kotlin, Jetpack Compose và Material 3 với công việc được lưu bền vững bằng Room. Project nằm ngay tại thư mục `android/`, gồm một module `app`; package và application ID là `com.studyflow`.

## SyncEngine, outbox và API giả có trạng thái (29/10/2026)

- **Room v2:** migration `MIGRATION_1_2` giữ nguyên bảng `tasks` v1 và thêm `sync_outbox`, `sync_conflicts`; giữ cả schema JSON v1/v2, không destructive migration. Mọi chỉnh sửa cục bộ vẫn lưu task + PENDING nguyên tử. Các chỉnh sửa chưa gửi được gom trong task; khi chạy sync, engine chụp snapshot vào outbox trong transaction **trước** API. Một task có tối đa một request chưa giải quyết, có UUID, operation, baseVersion, localRevision, payload, số lần thử, trạng thái/lỗi và mốc Retry-After.
- **SyncEngine:** gọi `suspend sync()` với `RoomSyncStore` và `TaskApi` được cung cấp rõ ràng; các engine dùng cùng instance database chia sẻ coordinator. Lượt chạy tiếp tục request cũ trước, gửi tối đa một lần/task/lượt, kiểm tra response rồi tải snapshot đầy đủ có tombstone. Không giữ transaction khi gọi API. Kết quả có runId, số xác nhận/pending/conflict và lỗi theo giai đoạn; không log mô tả task.
- **Retry:** timeout, mất phản hồi, 5xx và response không hợp lệ giữ nguyên mutationId/request. Không có trạng thái IN_FLIGHT cần reset sau process death. Retry-After của 429 được lưu bền vững. 422 chặn cho tới khi người dùng thực sự sửa; request mới được tạo chỉ vì chỉnh sửa mới sau lỗi chắc chắn chưa commit. MUTATION_ID_REUSED/TASK_NOT_FOUND chặn và báo lỗi; không tự thay ID. CancellationException truyền lại caller. Chưa có retry tự động/WorkManager.
- **Bảo vệ dữ liệu:** response cũ chỉ xác nhận revision đã gửi; sửa/toggle/xóa mới hơn giữ nguyên và tiếp tục pending với baseVersion vừa xác nhận. Pull không thay thế receipt của request chưa rõ kết quả; không dùng timestamp chọn bên thắng hoặc coi ID vắng mặt là xóa. Xung đột giữ hai snapshot, khóa mutation riêng task đó; các task khác tiếp tục. Cùng nội dung (hoặc cùng ý định xóa) được đối chiếu hội tụ. Chưa nối giao diện chọn phiên bản với ghi thật.
- **Fake server:** `src/debug/.../sync/fake/StatefulFakeTaskApi.kt`, chỉ debug/test; trạng thái server và receipt trong bộ nhớ riêng, tồn tại suốt đời instance fake. Có sửa từ client khác, version, tombstone, receipt trước version/validation, từ chối ID dùng lại khác request; điều khiển timeout trước commit, mất phản hồi sau commit, 503, 422 và gate coroutine trước/sau commit. Test giữ fake khi tạo lại client/engine/database; tạo fake mới tương đương server test mới, không phải mô phỏng restart server bền vững.

**Production chưa đồng bộ server thật.** Không gắn fake hoặc SyncEngine vào Application/ViewModel/nút Đồng bộ; giao diện concept và dữ liệu người dùng được giữ nguyên. Công cụ chạy bước này là test độc lập, không thêm màn hình demo. Mỗi test Room dùng database in-memory hoặc file UUID có tiền tố test và tự đóng/dọn đúng file đó; không mở `studyflow.db`.

Chạy bằng Wrapper trong `android/` (cùng JBR/JVM criteria hiện có):

```powershell
.\gradlew.bat --offline --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
$env:ANDROID_SERIAL='emulator-5556'
.\gradlew.bat --offline --no-daemon :app:connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

Lượt cuối: **82 unit test đạt** (36 SyncEngine mới + 46 test cũ), **23 test Room đạt** (11 mới + 12 cũ), build debug/APK test và compile release thành công; lint **0 lỗi, 8 cảnh báo hiện có**. Test thiết bị chạy trên Medium_Phone API 37; không có fake class trong output release. Kết quả, bằng chứng và giới hạn được lưu tại [báo cáo](../docs/testing/sync-engine-phase5-results.md). Bước tiếp theo là adapter Retrofit kết nối backend thật theo cùng hợp đồng; scheduler nền và luồng xác nhận xung đột thật vẫn ngoài nhiệm vụ này.

## Đối chiếu giao diện tổng thể theo concept (28/10/2026)

Đã mở trực tiếp cả hai ảnh trong `docs/design/mockups/` và đối chiếu ảnh chụp runtime. Trang chủ dùng thẻ tiến độ tím, nhóm quá hạn chỉ hiện khi có dữ liệu; Công việc có tìm kiếm bo tròn, chip trạng thái tím, thẻ gọn và banner số công việc chờ. Biểu mẫu thêm/sửa giữ chung một luồng, dùng ô có nhãn, menu môn học, hàng chọn/bỏ ngày, chip ưu tiên và nút **Lưu công việc** cuối biểu mẫu. Đồng bộ và xung đột dùng cùng theme; phiên bản xung đột được chọn có viền/nền tím và khác biệt có nhãn chữ.

Room vẫn là nguồn chung cho công việc, tiến độ và số PENDING (gồm tombstone). Giữ nguyên CRUD, validation, no-op revision, schema, dependency/toolchain và màn hình Chi tiết đã hoàn thiện. Đã sửa lệch vị trí cuộn khi ẩn/hiện thanh tab: lưu mốc item theo key tại UI và phục hồi khi quay lại từ Chi tiết hoặc biểu mẫu; truy vấn/bộ lọc được giữ nguyên.

- Wrapper: `:app:assembleDebug` thành công; **46 unit test đạt**, **12 test Room đạt** trên Medium_Phone API 37; lint **0 lỗi, 8 cảnh báo cấu hình/phiên bản hiện có**. Manifest release không chứa UiDemoActivity hoặc quyền Internet.
- Runtime: dữ liệu rỗng; hôm qua/hôm nay/ngày mai; Trang chủ → Chi tiết → sửa/hoàn thành → Back; checkbox độc lập với mở Chi tiết; tìm kiếm trim/không phân biệt hoa thường kết hợp trạng thái; giữ bộ lọc và mốc cuộn; thêm/sửa, lỗi tiêu đề bắt buộc, chọn môn/ngày/ưu tiên, lưu và xác nhận bỏ bản nháp; xóa logic vẫn được đếm pending. Xung đột chưa chọn/đã chọn và xóa–sửa không ghi Room. Đã kiểm tra bàn phím, chiều rộng **320 dp**, cỡ chữ **200%** và nội dung dài.
- Mọi thao tác ghi thử dùng harness debug với **Room in-memory riêng**, không xóa hoặc seed database người dùng. Ảnh có banner MINH HỌA của harness; app thật không có banner này. Kích thước/cỡ chữ AVD được khôi phục sau kiểm tra.

Ảnh và XML đối chiếu nằm trong `app/build/verification/ui-concept/` (Git bỏ qua): `home-final-apk.png`, `tasks.png`, `editor-save.png`, `sync.png`, `conflict.png`, các biến thể chọn/xóa–sửa/nội dung dài, `*-narrow-large-final.png` và `scroll-final-*.png`. Các ảnh đã được mở xem để kiểm tra thứ bậc, màu, khoảng cách, độ cao thẻ, chip/nút và nội dung cuộn.

Khác concept có chủ ý: lời chào chung, ngày/số liệu thực; banner ghi **Chưa kết nối máy chủ**, không giả trạng thái mạng; thông tin lưu cục bộ yêu cầu bấm Lưu; xung đột so sánh đủ 6 trường và **không chọn sẵn**. Đồng bộ chưa có cơ chế mạng, nút bị khóa kèm lý do; xung đột chỉ là UI/demo debug. Không triển khai SyncEngine/outbox/API hoặc sửa DOCX/`contracts/`; chưa commit/push. Không có phần triển khai UI bị chặn; chưa kiểm tra TalkBack, thiết bị vật lý/API 26 hoặc render riêng từng Preview trong Android Studio.

## Chi tiết công việc theo concept (28/10/2026)

- Đã chỉnh **màn hình Chi tiết hiện có** theo `docs/design/mockups/studyflow-task-detail.png`: header quay lại/sửa, chip chờ đồng bộ, tiêu đề và chip môn học/hoàn thành, mô tả có viền, thẻ deadline/ưu tiên, thời điểm cập nhật, nút hoàn thành → sửa → xóa và thông tin lưu cục bộ. Icon sửa và nút Sửa dùng cùng callback/biểu mẫu; thanh tab vẫn ẩn.
- Tiếp tục đọc task theo ID từ Room. Chỉ đưa `LocalTask.localUpdatedAt` hiện có vào `TaskDetailUiState`, định dạng theo múi giờ thiết bị và làm mới zone khi resume; không sinh timestamp để hiển thị. Giữ Entity/model nghiệp vụ, schema, repository ghi dữ liệu, dependency, toolchain và hợp đồng API.
- Nội dung/nút cuộn được; header tự tăng chiều cao khi chữ lớn, không ép tiêu đề task xuống dòng theo ảnh. Màu/chip/icon dùng theme; trạng thái và quá hạn có nhãn chữ. Lỗi ghi vẫn giữ nội dung; xóa vẫn cần xác nhận tên công việc.
- Có 5 Preview debug trong `ui/taskdetail/TaskDetailPreviews.kt`: chưa hoàn thành, đã hoàn thành, nội dung dài/chữ lớn, không deadline/mô tả và quá hạn. Preview chỉ dựng UiState, không gọi repository/DAO. Nút “Tạo dữ liệu thử Chi tiết công việc” trong harness debug hiện có tạo fixture riêng trong **Room in-memory**, không mở hoặc ghi `studyflow.db`.

Kiểm tra bằng Wrapper: `:app:assembleDebug` thành công; `:app:testDebugUnitTest` **46 test đạt**, gồm ca mới kiểm tra timestamp từ repository và giữ dữ liệu/timestamp khi ghi thất bại; `:app:connectedDebugAndroidTest` **12 test Room đạt**; `:app:lintDebug` **0 lỗi, 8 cảnh báo** về cấu hình/phiên bản hiện có. `git diff --check` sạch.

Runtime trên Medium_Phone API 37 với Room thử riêng: mở từ Trang chủ/danh sách; sửa qua icon header và nút trong nội dung; lưu và Back giữ đúng nơi mở/bộ lọc; hoàn thành/mở lại; quá hạn biến mất khi hoàn thành; không deadline/mô tả; hủy/xác nhận xóa. Sau xóa trở lại danh sách, không còn route Chi tiết task đã xóa. Đã thử nội dung dài và nhóm nút ở **320 dp, font_scale=2.0**, mở xem ảnh để đối chiếu concept; khôi phục kích thước/cỡ chữ ban đầu. Ảnh/XML local ở `app/build/verification/task-detail-*` (Git bỏ qua).

Khác concept có chủ đích: môn học dùng tên từ mã hiện có (“Lập trình di động”); dữ liệu/ngày giờ là dữ liệu đang đọc; tiêu đề/header xuống dòng theo chiều rộng và cỡ chữ. Banner “MINH HỌA” trong ảnh test thuộc harness debug, không xuất hiện ở app thật. Chưa chạy TalkBack, thiết bị vật lý/API 26 hoặc render từng Preview riêng trong Android Studio; không có phần triển khai bị chặn. Không sửa DOCX hoặc `contracts/`, chưa commit/push.

## Hoàn thiện giao diện bước 3

- **Trang chủ** là tab mở đầu, hiển thị StudyFlow, lời chào và ngày theo locale/múi giờ thiết bị. Dữ liệu lấy từ cùng repository/Room với Công việc, không seed hoặc giữ một danh sách mutable riêng.
- **Tiến độ hôm nay** chỉ tính công việc chưa xóa có deadline đúng hôm nay; số hoàn thành nằm trong chính tập đó. Khi không có deadline hôm nay, hiển thị thông điệp thay cho tỷ lệ 100%.
- **Quá hạn / Sắp đến hạn** chỉ gồm công việc chưa hoàn thành, chưa xóa, có deadline trước hôm nay / từ hôm nay trở đi. Mỗi nhóm tối đa 3 mục, sắp deadline tăng dần → HIGH/MEDIUM/LOW → ID. Bấm thẻ mở chi tiết hiện có theo ID; sửa, hoàn thành/mở lại hoặc xóa phản ánh lại từ Room. Back từ chi tiết trở về tab đã mở nó.
- **Xem tất cả** dùng lại tab Công việc: thay truy vấn cũ bằng truy vấn rỗng, Chưa xong và điều kiện ngày tương ứng. Chip “Hạn trước hôm nay” / “Hạn từ hôm nay” thể hiện điều kiện. Bấm chip hoặc “Xóa tìm kiếm và bộ lọc” bỏ toàn bộ điều kiện để xem tất cả. Danh sách được đưa về đầu khi mở từ Trang chủ.
- Loading lần đầu, lỗi đọc có Thử lại và dữ liệu thực sự rỗng là ba trạng thái riêng. Room phát cập nhật không bật loading lại. Ngày hiện tại được cập nhật bởi `MainActivity.onResume()` (và Activity minh họa), không có vòng lặp kiểm tra thời gian nền.
- **Đồng bộ** chỉ hiển thị trạng thái cục bộ: “Chưa kết nối máy chủ”, số **công việc** PENDING và “Lần đồng bộ gần nhất: Chưa có”. Số này tính cả tombstone PENDING, có chú thích số công việc đã xóa đang chờ gửi. Nút Đồng bộ ngay bị vô hiệu hóa kèm lý do bằng chữ. Không có gửi giả, timestamp giả hoặc chuyển PENDING thành SYNCED.
- **Xử lý xung đột** là composable thuần nhận `ConflictUiState` và callback. So sánh 6 trường, đánh dấu khác biệt bằng màu và chữ “Khác nhau”, chọn loại trừ nhau và không chọn sẵn. Nút xác nhận chỉ bật sau khi chọn; có cảnh báo bỏ thay đổi ở bản còn lại và có thể quay lại. Xóa–sửa dùng trạng thái Deleted riêng với “Giữ công việc” / “Xác nhận xóa”. Chưa có phát hiện hoặc giải quyết xung đột thực tế.

`observeStoredTasks()` đọc cả bản ghi đã xóa để tổng hợp metadata; mọi danh sách hoạt động đều loại chúng. Không đổi schema Room v1, dependency, toolchain, quyền mạng hoặc `contracts/`. Chưa thêm SyncEngine, outbox, API, Retrofit hay WorkManager.

### Preview và chạy thử bằng dữ liệu riêng

Các Preview trong `src/debug/java/com/studyflow/ui/conflict/ConflictPreviews.kt` có sửa–sửa chưa chọn/đã chọn, xóa–sửa chưa chọn/đã chọn và nội dung dài với fontScale 2. Chúng dùng dữ liệu minh họa trong debug resources.

APK debug còn có Activity **“Xem giao diện minh họa”**, chỉ mở bằng lệnh bên dưới; không có entry hoặc badge xung đột trong luồng sử dụng chính. Activity dùng Room **in-memory** riêng, ban đầu rỗng, có nút tạo dữ liệu hôm qua/hôm nay/ngày mai/không deadline. Có thể thử thêm/sửa/hoàn thành/lọc ở đó. Nút giữ phiên bản xung đột chỉ xác nhận lựa chọn minh họa, không ghi Room hoặc gửi máy chủ. Đóng process làm mất dữ liệu thử; database `studyflow.db` không được mở bởi harness này.

```powershell
adb -s emulator-5556 shell am start -n com.studyflow/.ui.demo.UiDemoActivity
```

`UiDemoActivity`, manifest entry, fixtures và Preview nằm hoàn toàn trong `src/debug/`, không được đóng gói vào release. Không dùng `pm clear` hoặc uninstall để thử trạng thái rỗng của app thật.

## Mở project và chuẩn bị môi trường

Trong Android Studio, chọn **Open** và mở thư mục `StudyFlow/android/` có tệp `settings.gradle.kts`, giữ cấu hình JVM hiện có rồi đồng bộ project. Dùng bản Android Studio hỗ trợ AGP 9.4.

Checkout hiện có cấu hình Daemon JVM criteria cho **JetBrains JBR 25** trong `gradle/gradle-daemon-jvm.properties`; giữ nguyên cấu hình đã build được này. JVM target của mã vẫn là 17. Các ví dụ `JAVA_HOME` bên dưới chọn JVM chạy Wrapper; Gradle có thể dùng daemon theo criteria đã lưu.

| Thành phần | Phiên bản cấu hình |
| --- | --- |
| JVM chạy Wrapper / JVM target của mã | 17 |
| Daemon JVM criteria hiện có | JetBrains JBR 25 |
| Gradle Wrapper | 9.6.0 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin / Compose compiler | 2.2.10; AGP cung cấp Kotlin tích hợp |
| Compose BOM | 2026.02.01 |
| Activity Compose | 1.13.0 |
| Lifecycle | 2.11.0 |
| Coroutines / coroutines-test | 1.10.2 |
| Room / KSP2 | 2.8.4 / 2.3.10 |
| AndroidX Test runner/core / JUnit extension | 1.7.0 / 1.3.0 |
| Android SDK | compileSdk 37, targetSdk 36, minSdk 26 |
| SDK Build Tools | 36.0.0, mặc định của AGP 9.4 |

Các dependency được quản lý trong `gradle/libs.versions.toml`. Wrapper có sẵn trước giai đoạn này dùng Gradle 9.3.0; chỉ URL distribution được đổi sang 9.6.0 để đáp ứng phiên bản tối thiểu của AGP 9.4 đã có trong môi trường. Các script Wrapper có sẵn được giữ nguyên. Yêu cầu tương thích được đối chiếu với [tài liệu AGP 9.4 chính thức](https://developer.android.com/build/releases/agp-9-4-0-release-notes).

Cần Android SDK Platform 37, Build Tools 36.0.0 và Platform Tools để build/cài APK. Chọn SDK trong Android Studio hoặc tạo `android/local.properties` trỏ tới SDK hiện có, ví dụ:

```properties
sdk.dir=C:/Android/Sdk
```

Thay đường dẫn bằng SDK thực tế. `local.properties`, thư mục `.gradle/` và output `build/` đã được Git bỏ qua; không đưa cấu hình đường dẫn máy cá nhân vào commit. Lần build đầu cần mạng để tải những dependency chưa có trong cache.

## Build và chạy

Mở terminal tại `StudyFlow/android/`. Trên máy kiểm tra, `java` trên `PATH` trỏ tới Java 8 nên phải chọn JDK/JBR 17 bằng `JAVA_HOME`. Trong PowerShell, thay đường dẫn dưới đây bằng JDK 17 đã cài:

```powershell
$env:JAVA_HOME = 'C:\Path\To\jdk-17'
& "$env:JAVA_HOME\bin\java.exe" -version
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

Trên macOS/Linux, đặt `JAVA_HOME` tới JDK 17 rồi chạy:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK debug được tạo tại `app/build/outputs/apk/debug/app-debug.apk`. Để chạy, chọn emulator hoặc thiết bị Android API 26 trở lên trong Android Studio và bấm **Run**. Với thiết bị đã bật USB debugging hoặc emulator đang chạy, cũng có thể dùng Platform Tools:

```powershell
adb devices
.\gradlew.bat :app:installDebug
adb shell am start -n com.studyflow/.MainActivity
```

Nếu `adb` chưa có trên `PATH`, gọi `adb` trong thư mục `platform-tools` của SDK. Khi có nhiều thiết bị, chọn đúng thiết bị trong Android Studio hoặc dùng `adb -s <serial>` để cài và mở APK.

### Run báo `error: closed` trước khi cài/mở app

Lần kiểm tra ngày 28/10/2026 phát hiện thiết bị `NE2211 / emulator-5554` được chọn trong IDE đóng kênh ADB khi chạy `pm`/`am`; build APK vẫn thành công. Đã chuyển sang AVD có sẵn **Medium Phone** (`Medium_Phone`, API 37) ở cổng 5556 và xác nhận Run từ Android Studio cài rồi mở `MainActivity` thành công.

Khi gặp trường hợp này, mở Device Manager, khởi động **Medium Phone**, chọn thiết bị đó trên thanh Run và chọn cấu hình **app**. Dùng `adb devices -l` để đối chiếu serial; trạng thái `device` riêng lẻ chưa chứng minh các lệnh cài/mở hoạt động. Nếu cổng 5554 đã bị thiết bị khác chiếm, có thể mở AVD ở cổng 5556:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone -port 5556
```

Sau khi Android boot xong, cài/mở rõ thiết bị đích, giữ nguyên dữ liệu:

```powershell
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 shell am start -W -n com.studyflow/.MainActivity
```

### Lỗi socket cục bộ của Gradle/JDK trên Windows

Trong lần kiểm tra tại môi trường này, Gradle chạy được sau khi đặt thư mục tạm cho UNIX domain socket sang `android/.gradle/tmp`. Nếu gặp lỗi tạo socket cục bộ tương tự, dùng một thư mục tồn tại, có quyền ghi và đường dẫn ngắn. [Tài liệu Java 17](https://docs.oracle.com/en/java/javase/17/core/java-networking.html) mô tả thuộc tính `jdk.net.unixdomain.tmpdir` và giới hạn độ dài đường dẫn socket.

Chạy từ `android/` với JDK 17 đã chọn:

```powershell
$studyFlowSocketDir = Join-Path (Get-Location).Path '.gradle\tmp'
New-Item -ItemType Directory -Force -Path $studyFlowSocketDir | Out-Null
$studyFlowPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $studyFlowSocketOption = '-Djdk.net.unixdomain.tmpdir="' + $studyFlowSocketDir + '"'
    $env:JAVA_TOOL_OPTIONS = ($studyFlowPreviousJavaOptions + ' ' + $studyFlowSocketOption).Trim()
    .\gradlew.bat --no-daemon :app:assembleDebug :app:testDebugUnitTest
} finally {
    $env:JAVA_TOOL_OPTIONS = $studyFlowPreviousJavaOptions
}
```

Thuộc tính này chỉ áp dụng cho các tiến trình Java được khởi chạy trong đoạn lệnh và được khôi phục sau đó. Không cần sửa cấu hình mạng toàn hệ thống hoặc tệp cấu hình JDK. Nếu repo nằm trong đường dẫn quá dài, chọn một thư mục tạm ngắn hơn thuộc quyền ghi của bạn.

## Chức năng hiện có

- Giữ theme tím sáng, ba tab và các luồng của giai đoạn 2: thêm, chi tiết, sửa, hoàn thành/mở lại, xác nhận xóa logic và cảnh báo bỏ bản nháp chưa lưu.
- Database mới **bắt đầu rỗng**. Không tự seed, không nhập dữ liệu mẫu từ bộ nhớ của giai đoạn trước. Fixture `SampleTasks` đã chuyển sang `src/debug/` để dành cho preview; không được gọi từ Application/Activity/repository.
- Tìm tiêu đề bằng trim/không phân biệt hoa thường, kết hợp lọc Tất cả/Chưa xong/Hoàn thành; sắp deadline → ưu tiên → ID. Task đã xóa không xuất hiện trong tìm kiếm, lọc hoặc tổng số đang hoạt động.
- Biểu mẫu giữ quy tắc tên 1–120 ký tự sau trim, mô tả tối đa 2.000 ký tự, môn `OTHER`/ưu tiên `MEDIUM`/deadline `null` mặc định. UUID tạo ở thiết bị. Chọn/xóa ngày và ngày quá khứ vẫn được hỗ trợ.
- Lưu trên thiết bị trước khi quay lại danh sách/chi tiết; trạng thái Đang lưu ngăn lưu lặp. Lỗi giữ nguyên bản nháp và cho phép bấm Lưu để thử lại. Mutation chạy trong `viewModelScope`, không phụ thuộc coroutine của composable.
- Chi tiết quan sát riêng theo ID, cập nhật khi repository thay đổi, kể cả khi task không còn khớp truy vấn của danh sách. Task thiếu/đã xóa có trạng thái phù hợp. Xóa cần xác nhận, giữ tombstone trong database và trở về danh sách sau khi ghi thành công.
- Phân biệt đang đọc lần đầu, lỗi đọc (có Thử lại), danh sách rỗng và không có kết quả lọc. Flow phát dữ liệu mới không đưa màn hình về loading. Lỗi ghi/xóa không hiện thành công; thao tác có thể thử lại.
- Giữ truy vấn, bộ lọc và vị trí cuộn qua điều hướng/tab; bản nháp vẫn giữ qua xoay màn hình, nhưng bản nháp **chưa bấm Lưu** không thuộc cam kết lưu bền vững.

## Kiến trúc dữ liệu

```text
SQLite / Room TaskDao (suspend + Flow)
  → RoomTaskRepository (transaction, mapping, kết quả ghi rõ ràng)
  → TasksViewModel (Flow → StateFlow, tìm kiếm/lọc, bản nháp và route theo ID)
  → Compose (collectAsStateWithLifecycle)
```

`StudyFlowApplication` cung cấp một database/repository bằng lazy manual DI dùng `applicationContext`. ViewModel chỉ nhận `TaskRepository`, không giữ DAO hoặc một danh sách mutable song song. Dữ liệu cho danh sách và chi tiết được suy ra từ Flow của Room; `stateIn(WhileSubscribed(5_000))` quản lý việc quan sát theo subscriber. Không có coroutine tự chạy trong constructor/repository hoặc phạm vi nền tách khỏi lifecycle. Truy vấn/bộ lọc và bản nháp/route được expose trực tiếp qua `conditionsState`/`navigationState` để TextField nhận giá trị mới ngay trong sự kiện nhập, không đi vòng qua `combine/stateIn`; dữ liệu chi tiết vẫn là state suy ra từ Flow theo ID.

`TaskEntity` là dữ liệu lưu trữ; mapper tạo `LocalTask` gồm model nghiệp vụ `Task` và metadata cục bộ; `TaskItem` là state trình bày. `TaskContent` chỉ gồm trường biểu mẫu sở hữu. Lưu biểu mẫu dùng UPDATE từng trường, không upsert snapshot toàn bộ và không ghi đè hoàn thành, tombstone hoặc metadata vừa đổi.

Room suspend/Flow tự điều phối truy vấn; không dùng `allowMainThreadQueries`, không bọc mọi lời gọi trong `Dispatchers.IO`. Repository chuyển lỗi SQLite/giải mã thành `TaskStorageException`; cancellation được ném lại. UI hiển thị lỗi thay vì xem truy vấn hỏng là kết quả rỗng.

## Schema và metadata cục bộ

Đây là schema Room đầu tiên, version **1**, `exportSchema=true`. Schema JSON thật do KSP sinh tại `app/schemas/com.studyflow.data.local.database.StudyFlowDatabase/1.json` được giữ cùng mã nguồn để đưa vào commit khi được yêu cầu. Chưa cần migration từ schema cũ và không bật destructive fallback. Database runtime là `studyflow.db` trong sandbox ứng dụng; không đưa database/wal/shm vào Git.

- Khóa chính: UUID dạng chuỗi; enum lưu `.name`, không ordinal.
- Deadline là ngày lịch `LocalDate?`, lưu ISO `YYYY-MM-DD` nullable; không chuyển thành timestamp UTC. UTC trong DatePicker chỉ dùng chuyển đổi API lịch của Material 3, không phải cách lưu deadline.
- `baseVersion: Long`: mặc định **0** cho task tạo mới. Không tự tăng, không giả lập phiên bản server.
- `localRevision: Long`: bắt đầu **1**, tăng trong câu UPDATE bằng `localRevision + 1` khi có thay đổi nghiệp vụ thật.
- `localUpdatedAt: Long`: epoch milliseconds từ đồng hồ thiết bị, chỉ dành cho hiển thị/log về sau; không dùng để quyết định bản thắng khi xung đột.
- `syncState`: `PENDING`/`SYNCED` là mã cục bộ. Tạo/sửa thật/hoàn thành/xóa đều đặt `PENDING` cùng transaction với dữ liệu. Hiện không có API hoặc thao tác nào tự chuyển sang `SYNCED`.

Câu UPDATE chỉ tác động `isDeleted=0`. So sánh nội dung/ngày nullable ngay trong SQL loại no-op; đặt hoàn thành về cùng giá trị cũng không đổi revision, timestamp hoặc sync state. Số hàng cập nhật được đối chiếu trong transaction để phân biệt thành công/không đổi với ID thiếu/đã xóa. Xóa chỉ đặt `isDeleted=true`, không gọi DELETE vật lý.

Các tên và kiểu metadata trên là thiết kế **cục bộ**, chưa phải wire format backend. Hợp đồng API đã được chuyển riêng cho người làm backend; nhiệm vụ giao diện này không sửa hoặc bổ sung `contracts/`, và placeholder trong checkout không cản phần giao diện. Chưa tạo DTO mạng, mutationId, outbox, SyncEngine, Retrofit, WorkManager hoặc giải quyết xung đột thực tế.

Room 2.8.4 và KSP2 2.3.10 được chọn phù hợp Kotlin/AGP hiện có và cache của môi trường; không đổi Gradle/AGP/Kotlin/Compose. Đã đối chiếu [hướng dẫn Room/KSP và schema](https://developer.android.com/jetpack/androidx/releases/room), [KSP 2.3.10](https://github.com/google/ksp/releases/tag/2.3.10) và [Kotlin tích hợp của AGP](https://developer.android.com/build/migrate-to-built-in-kotlin). Schema dùng KSP `CommandLineArgumentProvider` với thư mục output được Gradle theo dõi, không cần thêm Room Gradle plugin chỉ để cấu hình đường dẫn.

## Chạy test

Tại `android/`, chọn JDK 17 và dùng Wrapper như phần trên:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:lintDebug
```

`connectedDebugAndroidTest` cần thiết bị/emulator có sẵn; không tự tải system image. Unit test dùng fake repository và Main dispatcher dùng chung scheduler với `runTest`, giữ các ca của giai đoạn trước và bổ sung trạng thái tải/lỗi/thử lại, save đang chờ, cancellation và cập nhật bên ngoài.

Instrumented test dùng Room thật: database in-memory và file riêng có tên `studyflow-test-<UUID>.db`. Test chỉ dọn các file do chính test tạo, không đụng `studyflow.db` của ứng dụng. Các ca bao phủ Flow với timeout, enum/ngày nullable, baseVersion/ID, revision và no-op, trường biểu mẫu không đè hoàn thành, tombstone không bị phục hồi, ID thiếu, ghi đồng thời, lỗi SQLite/rollback và đóng/mở lại cùng file.

## Tự kiểm tra lưu bền vững

1. Cài APK bằng `installDebug` hoặc `adb install -r` (không gỡ ứng dụng/xóa dữ liệu).
2. Tắt Wi-Fi và dữ liệu di động trên emulator, mở Công việc; database mới phải rỗng.
3. Thêm việc, mở chi tiết, sửa tên/mô tả/deadline và đổi hoàn thành. Kiểm tra tìm kiếm/lọc.
4. Dừng tiến trình bằng `adb shell am force-stop com.studyflow`, rồi mở bằng `adb shell am start -n com.studyflow/.MainActivity`. Công việc đã Lưu phải giữ nguyên.
5. Xóa logic một việc, force-stop/mở lại; việc đó không được xuất hiện trở lại. Nếu đã xóa tất cả, danh sách rỗng vẫn hợp lệ và không được gieo mẫu lại.
6. Có thể khởi động lại thiết bị test để kiểm tra thêm. Không dùng `pm clear` hoặc uninstall để mô phỏng đóng/mở app vì chúng xóa database.

Công việc đã lưu tồn tại sau khi Activity/ViewModel hoặc process được tạo lại và sau khởi động thiết bị. Gỡ ứng dụng/xóa dữ liệu sẽ xóa database. Chưa có đồng bộ server hoặc bản sao giữa thiết bị; nhãn Chờ đồng bộ phản ánh dữ liệu cục bộ đang chờ bước đồng bộ trong tương lai.

## Kiểm tra phần giao diện bổ sung (28/10/2026)

Giữ nguyên dependency/toolchain hiện có, dùng Wrapper offline và daemon theo JVM criteria đã lưu:

| Lệnh | Kết quả thực tế |
| --- | --- |
| `:app:assembleDebug` | Thành công trên mã mới nhất |
| `:app:testDebugUnitTest` | 45 test đạt, 0 lỗi/thất bại/bỏ qua; gồm 11 test mới về Trang chủ/bộ lọc/metadata và 3 test model trình bày xung đột |
| `:app:connectedDebugAndroidTest` | 12 test Room đạt trên Medium_Phone API 37, 0 lỗi/thất bại/bỏ qua; thêm ca đếm tombstone từ stored Flow |
| `:app:lintDebug` | Thành công, 0 lỗi và 9 cảnh báo cấu hình/phiên bản hiện có |
| `:app:processReleaseMainManifest` | Thành công; manifest release không có UiDemoActivity hoặc quyền Internet |

Ngày kiểm thử logic được cố định ở 28/10/2026 và chủ động chuyển sang hôm sau; không phụ thuộc ngày chạy test. Có ca kiểm tra tiến độ đúng tập hôm nay, hoàn thành/mở lại, tổng bằng 0, ranh giới ngày, task không deadline, task hoàn thành/đã xóa, giới hạn/thứ tự nhóm, pending tính theo bản ghi, truy vấn cũ, bỏ bộ lọc, cập nhật Room và loading/lỗi/retry. Test Room dùng file test hoặc database trong bộ nhớ riêng.

Rà soát UX theo `android-ux`: dùng token màu/typography/shape hiện có, component Material 3, action tối thiểu 48 dp, tiêu đề section có heading, nội dung cuộn và khác biệt có nhãn chữ. Theme sáng tĩnh được giữ theo yêu cầu; không thêm dynamic color, dark mode hoặc animation. Body giới hạn 840 dp; chưa bổ sung rail/foldable riêng ngoài phạm vi hiện tại.

Kiểm tra runtime trên APK mới nhất, Medium_Phone / emulator-5556, bằng **UiDemoActivity với Room in-memory riêng**:

- [x] Trang chủ ban đầu rỗng, có hướng dẫn tạo và thông điệp không có deadline hôm nay.
- [x] Tạo 4 task thử qua repository: hôm qua, hôm nay, ngày mai và không deadline; Trang chủ có đúng nhóm và tiến độ 0/1.
- [x] “Xem tất cả” của nhóm quá hạn mở Công việc với Chưa xong + chip hạn trước hôm nay; bỏ điều kiện hoạt động.
- [x] Mở chi tiết từ Trang chủ, sửa tên rồi Back về đúng Trang chủ; tên phản ánh dữ liệu đã lưu. Hoàn thành task hôm nay từ chi tiết rồi Back: tiến độ đổi 1/1.
- [x] Xóa logic một task thử: Đồng bộ vẫn đếm 4 bản ghi PENDING và chú thích 1 công việc đã xóa; nút Đồng bộ ngay có enabled=false trong cây accessibility.
- [x] Sửa–sửa hiển thị khác biệt; nút giữ phiên bản có enabled=false lúc chưa chọn và true sau khi chọn. Bấm xác nhận chỉ cập nhật thông báo minh họa.
- [x] Xóa–sửa hiển thị Deleted riêng và hai lựa chọn giữ/xóa. Sau thử giao diện xung đột, dữ liệu thử vẫn là tiến độ 1/1, pending 4, tombstone pending 1.
- [x] Nội dung dài cuộn được; chụp/đối chiếu Trang chủ, Đồng bộ và xung đột với font_scale=2.0. Đã khôi phục font_scale=1.0 ban đầu.

Ảnh và XML thực tế ở `app/build/verification/home-sync-*` (Git bỏ qua). Phiên cập nhật APK trên AVD từng bị Package Manager giữ pending; khởi động lại AVD rồi cài `--no-streaming -r` thành công, giữ userdata. Không tải SDK/system image, không clear data hoặc uninstall. Các test dữ liệu dùng Room riêng; app thật không nhận fixture minh họa.

Sau kiểm tra đã đóng harness (giải phóng Room in-memory), khôi phục cỡ chữ và mở `MainActivity` thật. Trang chủ hiển thị, không có AndroidRuntime error của tiến trình app trong lần kiểm tra cuối; giữ giả lập mở để sử dụng.

Giới hạn: chưa chạy trên thiết bị vật lý/API 26, TalkBack hoặc màn hình gập/tablet. Preview đã được compile trong debug và composable được thử qua harness; chưa render từng Preview riêng trong Android Studio. Ngày đổi được kiểm tra bằng provider cố định trong unit test, chưa đổi ngày/múi giờ hệ thống của giả lập. Đồng bộ và giải quyết xung đột thật vẫn chưa được triển khai theo phạm vi nhiệm vụ.

## Kiểm tra Room đã thực hiện trước phần giao diện bổ sung

Ngày 28/10/2026, dùng Wrapper và JBR 17:

| Kiểm tra | Kết quả |
| --- | --- |
| `:app:assembleDebug` | Thành công trên mã cuối |
| `:app:testDebugUnitTest` | 31 test đạt: 9 danh sách + 15 luồng giai đoạn 2 + 7 tải/lỗi/coroutine/nhập liệu; 0 lỗi/thất bại/bỏ qua |
| `:app:assembleDebugAndroidTest` | Thành công, APK chứa 11 test Room thật |
| `:app:connectedDebugAndroidTest` | Thành công: 11 test Room, 0 thất bại/bỏ qua, AVD Medium_Phone API 37 |
| `:app:lintDebug` | Thành công, 0 lỗi và 9 cảnh báo |

Chín cảnh báo lint liên quan targetSdk, phiên bản Gradle/Compose BOM/Kotlin/coroutines/Room mới hơn và `dataExtractionRules`. Không nâng toolchain hoặc thêm suppression chỉ để bỏ cảnh báo.

Báo cáo unit test: `app/build/reports/tests/testDebugUnitTest/index.html`; lint: `app/build/reports/lint-results-debug.html`. Schema JSON đã sinh được kiểm tra trong working tree. `git diff --check` không báo lỗi khoảng trắng. Chưa commit/push; giữ nguyên tệp `android.zip` đã có.

Test file database đã thực sự lưu hai bản ghi, sửa/hoàn thành một bản và xóa logic bản còn lại, đóng database rồi mở lại **cùng file**. Dữ liệu, tombstone, `baseVersion`, revision, timestamp và sync state khớp trước khi đóng. Sau khi xóa nốt bản đang hoạt động rồi đóng/mở lại, danh sách vẫn rỗng và tombstone còn nguyên. Test chỉ dọn file UUID của chính test.

Báo cáo thiết bị: `app/build/reports/androidTests/connected/debug/index.html`; JUnit/logcat: `app/build/outputs/androidTest-results/connected/debug/`. Lệnh chạy thiết bị có thêm `'-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'` (đặt trong dấu nháy ở PowerShell) để giữ APK sau test.

Kiểm tra UI/lưu bền vững đã thực hiện trên APK cuối, với Wi-Fi và dữ liệu di động tắt (`Active default network: none`):

- [x] Database production mới mở rỗng; không có task mẫu.
- [x] Nhập nhanh tên và truy vấn tìm kiếm chính xác; có test hồi quy cho đường state nhập liệu trực tiếp.
- [x] Thêm `RoomPersist`, xem chi tiết, sửa thành `RoomPersistEdit`, thêm mô tả `OfflineSaved`, đánh dấu hoàn thành.
- [x] Force-stop rồi mở lại bằng tiến trình mới (PID thay từ 11927 sang 13914): tên, mô tả và trạng thái hoàn thành vẫn còn.
- [x] Tìm `RoomPersist` kết hợp lọc Hoàn thành và mở chi tiết vẫn cho đúng dữ liệu đã lưu.
- [x] Xóa logic, force-stop/mở lại: công việc không xuất hiện trở lại.
- [x] Xóa nốt bản ghi do phiên kiểm tra tạo, force-stop/mở lại: danh sách vẫn rỗng, không seed mẫu lại.

Không dùng `pm clear`, uninstall hoặc xóa database production để kiểm tra đóng/mở. Các ảnh/XML nằm trong `app/build/verification/phase3-*`, được Git bỏ qua. Đã cài APK bằng `install -r`; các instrumented test chỉ mở và dọn file database riêng của test.

AVD có lần boot rất chậm và System UI hiện ANR. Đã khởi động lạnh lại AVD hiện có bằng `-no-snapshot-load -no-snapshot-save -feature -Vulkan -memory 3072`, giữ nguyên userdata, rồi chọn Wait ở hộp thoại System UI. Sau đó hoàn tất các kiểm tra trên; Wi-Fi/dữ liệu được khôi phục trước khi đóng emulator do phiên này khởi động. Không tải SDK/system image mới.

Chưa kiểm tra thiết bị vật lý, runtime API 26, TalkBack hoặc reboot thiết bị sau khi lưu. Bản nháp chưa lưu không được bảo đảm qua process death. Chưa có đồng bộ server.

## Bước tiếp theo

Adapter Retrofit kết nối backend thật theo cùng hợp đồng. SyncEngine/outbox và fake có trạng thái đã được triển khai ở bước 5; production vẫn chưa gọi server. WorkManager và luồng xác nhận xung đột thật cần nhiệm vụ tiếp theo.
