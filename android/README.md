# StudyFlow Android — giai đoạn 3

Ứng dụng Kotlin, Jetpack Compose và Material 3 với công việc được lưu bền vững bằng Room. Project nằm ngay tại thư mục `android/`, gồm một module `app`; package và application ID là `com.studyflow`.

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

`contracts/sync-protocol.md` hiện vẫn là placeholder: chưa thống nhất wire format, kiểu phiên bản server, receipt hoặc mutation. Các tên và kiểu trên là thiết kế **cục bộ**, chưa phải giao thức backend. Chưa tạo DTO mạng, mutationId, outbox, SyncEngine, Retrofit, WorkManager hoặc giải quyết xung đột.

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

## Kiểm tra thực tế

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

Chưa kiểm tra thiết bị vật lý, runtime API 26, TalkBack hoặc reboot thiết bị sau khi lưu. Bản nháp chưa lưu không được bảo đảm qua process death. Chưa có đồng bộ server; hợp đồng vẫn cần chốt trước giai đoạn SyncEngine.

## Bước tiếp theo

SyncEngine với API giả và outbox, sau khi chốt phần hợp đồng còn thiếu. Giai đoạn này dừng ở Room, chưa triển khai API, WorkManager hoặc đồng bộ.
