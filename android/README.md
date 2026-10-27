# StudyFlow Android — giai đoạn 1

Ứng dụng Kotlin, Jetpack Compose và Material 3 với dữ liệu mẫu trong bộ nhớ. Project nằm ngay tại thư mục `android/`, gồm một module `app`; package và application ID là `com.studyflow`.

## Mở project và chuẩn bị môi trường

Trong Android Studio, chọn **Open** và mở thư mục `StudyFlow/android/` có tệp `settings.gradle.kts`. Chọn Gradle JDK 17 trong phần cài đặt Gradle, rồi đồng bộ project. Dùng bản Android Studio hỗ trợ AGP 9.4.

| Thành phần | Phiên bản cấu hình |
| --- | --- |
| JDK / JVM target | 17 |
| Gradle Wrapper | 9.6.0 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin / Compose compiler | 2.2.10; AGP cung cấp Kotlin tích hợp |
| Compose BOM | 2026.02.01 |
| Activity Compose | 1.13.0 |
| Lifecycle | 2.11.0 |
| Coroutines | 1.10.2 |
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

- Theme sáng với màu tím `#6355D9`, nền `#FAFAFC`, chữ chính `#17172B`; màu môn học pastel, typography và shape dùng chung. Chưa bật dynamic color hoặc theme tối.
- Ba tab có icon và nhãn: Trang chủ, Công việc, Đồng bộ. Công việc mở mặc định; hai tab còn lại hiển thị nội dung giới thiệu rõ phạm vi.
- Sáu công việc mẫu có UUID cố định, đủ bốn môn học, ba mức ưu tiên, việc hoàn thành/chưa xong và deadline quá hạn/hôm nay/sắp tới/không có hạn. Ngày mẫu tính tương đối một lần khi tạo ViewModel.
- Tìm theo tiêu đề không phân biệt hoa thường, bỏ khoảng trắng đầu/cuối khi đối sánh, kết hợp bộ lọc Tất cả/Chưa xong/Hoàn thành. Có nút xóa truy vấn và nút xóa cả tìm kiếm/bộ lọc khi không có kết quả.
- Danh sách sắp deadline tăng dần, không có hạn nằm cuối; cùng deadline thì HIGH → MEDIUM → LOW, sau đó theo ID. `LazyColumn` dùng ID làm key ổn định.
- Checkbox cập nhật ngay trong bộ nhớ và giữ danh sách gốc khi đang lọc. Thẻ có nhãn môn học, ưu tiên, deadline, trạng thái hoàn thành và nhãn đồng bộ minh họa. Việc quá hạn chưa hoàn thành có nhãn bằng chữ.
- Nút cộng và thao tác bấm thẻ hiển thị Snackbar thông báo chức năng sẽ được bổ sung. Chưa có biểu mẫu hay màn hình chi tiết.
- Chuyển tab giữ công việc, truy vấn, bộ lọc và vị trí cuộn. ViewModel giữ state qua thay đổi cấu hình; Compose thu thập bằng `collectAsStateWithLifecycle`. Focus, scroll và Snackbar ở tầng UI.
- Giao diện dùng string resources tiếng Việt, vùng chạm tối thiểu 48 dp; nội dung có padding cho system bar, bàn phím và FAB.

## Giới hạn dữ liệu

Dữ liệu chỉ lưu trong bộ nhớ của `TasksViewModel`. Khi tiến trình ứng dụng được tạo lại, mẫu được tạo lại và các thay đổi hoàn thành/tìm kiếm/bộ lọc trước đó mất đi. Chưa có lưu trữ bền vững, tài khoản, backend hoặc đồng bộ thật.

“Chờ đồng bộ” và “Đã đồng bộ” chỉ là nhãn minh họa trong model trình bày. Đổi hoàn thành đặt nhãn thành “Chờ đồng bộ”, nhưng không gửi request hoặc tạo tác vụ đồng bộ. Không có trạng thái “Đang đồng bộ” hay số liệu hoạt động server giả.

Model nghiệp vụ dùng `LocalDate?` cho `dueDate`, enum `MOBILE/DATABASE/GENERAL/OTHER` và `LOW/MEDIUM/HIGH`; nhãn tiếng Việt ánh xạ ở UI. `contracts/sync-protocol.md` hiện là tài liệu placeholder chưa chốt wire format. Giai đoạn này không sửa hợp đồng, không tạo DTO/entity Room và không suy diễn revision hoặc payload server. Chưa thêm Room, Retrofit, WorkManager hay framework DI.

## Kiểm tra thực tế

Kết quả ngày 27/10/2026:

| Kiểm tra | Kết quả |
| --- | --- |
| `:app:assembleDebug` bằng Wrapper | Đã chạy thành công trên mã cuối; APK được cài và khởi chạy lạnh thành công |
| `:app:testDebugUnitTest` | Đã chạy thành công: 9 test, 0 lỗi, 0 thất bại, 0 bỏ qua |
| `:app:lintDebug` | Đã chạy thành công: 0 lỗi, 6 cảnh báo |
| Chạy và kiểm tra UI trên emulator | Đã cài APK và mở thành công trên AVD `Medium_Phone`, `emulator-5556`, Android API 37 (system image 37.2) |

Các unit test bao phủ tìm kiếm kết hợp lọc, trim/không phân biệt hoa thường, toggle khi đang lọc, bảo toàn dữ liệu gốc, thứ tự deadline/ưu tiên/ID, deadline null, quá hạn và ngày thay đổi, nguồn rỗng so với không có kết quả, tìm theo tiêu đề và ID không tồn tại. Báo cáo JUnit nằm tại `app/build/reports/tests/testDebugUnitTest/index.html`; báo cáo lint nằm tại `app/build/reports/lint-results-debug.html`.

Sáu cảnh báo lint gồm targetSdk 36 chưa là phiên bản mới nhất, các bản mới hơn của Gradle/Compose BOM/Kotlin/coroutines và cấu hình backup `dataExtractionRules`. Chưa nâng công cụ hoặc thêm suppression chỉ để loại các cảnh báo này; ứng dụng đang đặt `allowBackup="false"`.

Checklist UI đã thực hiện trên emulator:

- [x] Ba tab mở đúng nội dung; chuyển tab giữ truy vấn, bộ lọc, hoàn thành và vị trí cuộn.
- [x] Truy vấn ` sQl ` kết hợp bộ lọc cho kết quả đúng, không phân biệt hoa thường và bỏ khoảng trắng khi đối sánh.
- [x] Toggle trong Chưa xong làm kết quả rỗng ngay; chuyển sang Hoàn thành thấy lại cùng công việc với nhãn Chờ đồng bộ.
- [x] Thứ tự hiển thị: quá hạn → hôm nay HIGH/MEDIUM/LOW → sắp tới → không có hạn.
- [x] Tổ hợp tìm kiếm/bộ lọc không khớp hiện trạng thái không có kết quả; nút xóa tìm kiếm/bộ lọc khôi phục danh sách gốc.
- [x] Checkbox không kích hoạt Snackbar của thẻ; thẻ và FAB hiện đúng thông báo đã yêu cầu.
- [x] Bàn phím hiển thị và ô tìm kiếm dùng được; cuộn cuối danh sách có khoảng trống cho FAB, nội dung không sát system bar.
- [x] Xoay màn hình giữ truy vấn và hoàn thành. Đã xem ảnh giao diện ở kích thước thường và chiều rộng 320 dp với font scale 2; nhãn xuống dòng và vẫn đọc được.

Ảnh chụp và XML giao diện kiểm tra nằm trong `app/build/verification/`, được Git bỏ qua cùng các báo cáo build. Chưa chạy instrumented test, thiết bị vật lý hoặc runtime API 26. Lỗi ADB ban đầu được xử lý bằng AVD có sẵn; lỗi socket JDK đã xử lý bằng thư mục tạm ngắn như hướng dẫn trên, không còn chặn các kiểm tra đã nêu.

## Bước tiếp theo

Triển khai biểu mẫu thêm/sửa công việc trước, sau đó tích hợp Room làm nguồn dữ liệu cục bộ. Đồng bộ và API thuộc các giai đoạn tiếp theo, sau khi hợp đồng được thống nhất.
