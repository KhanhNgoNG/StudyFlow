# StudyFlow — kết quả bước 5

Ngày thực hiện: **29/10/2026**, múi giờ Asia/Saigon.

## Phạm vi và nguồn hợp đồng

Triển khai SyncEngine, outbox Room bền vững, lưu snapshot xung đột và API giả có trạng thái. Không thay giao diện concept, DOCX, `contracts/`, dependency/toolchain; không thêm Retrofit, WorkManager, DI hoặc mạng thật. Không commit/push.

`contracts/sync-protocol.md` trong repo còn là placeholder. Đã đọc toàn bộ hợp đồng đầy đủ từ `C:/Users/Khanh Ngo/Documents/ChatGPT/mbplan/contracts/sync-protocol.md`, bản 1.0 đề xuất ngày 27/10/2026 mà người dùng đã gửi backend. SHA-256 lúc đối chiếu: `07106C89003662DC353489EAB7BE65B7113027CA566029DF5837B2E54188D5D3`. Không sao chép/sửa hợp đồng trong nhiệm vụ.

## Thay đổi đã lưu

- `sync/api/TaskApi.kt`: transport riêng; POST/PUT body đầy đủ, DELETE không body và có baseVersion trên query; Idempotency-Key; response task/list/error và Retry-After. Chưa có HTTP adapter/JSON parser; adapter sau này phải từ chối JSON thiếu trường bắt buộc, giữ đúng tên trường/endpoint của hợp đồng.
- `sync/SyncEngine.kt`, `SyncStore.kt`, `data/repository/RoomSyncStore.kt`: lượt sync suspend, tuần tự; xử lý response/receipt, pull có kiểm tra toàn bộ snapshot, đối chiếu version/nội dung, lỗi theo giai đoạn và runId. Caller sở hữu coroutine/cancellation. Không lưu scope hoặc tự chạy trong constructor/Application.
- Room v2 thêm `sync_outbox` và `sync_conflicts`. Một outbox row/task, mutationId có unique index; snapshot không đổi sau khi gửi, chỉ bookkeeping được cập nhật. `MIGRATION_1_2` chỉ thêm bảng/index, giữ nguyên DDL bảng tasks. KSP đã sinh `schemas/.../2.json`, giữ nguyên `1.json`.
- Repository vẫn ghi local trước. PENDING trong task là ý định bền vững cho các chỉnh sửa chưa gửi; chúng được gom trước lượt sync. Snapshot/request mới được chụp nguyên tử vào outbox trước API; không sửa snapshot cũ để gom chỉnh sửa đã có thể commit. Task đã xung đột bị chặn chỉnh sửa trong transaction, không ảnh hưởng task khác.
- `src/debug/.../StatefulFakeTaskApi.kt`: server/receipt riêng, version tăng nguyên tử với receipt thành công, receipt được kiểm tra trước version và validation. Có sửa từ client khác, đầy đủ currentTask khi conflict, GET gồm tombstone và điều khiển lỗi/gate không sleep.
- Không gắn fake vào production. Nút Đồng bộ của app chính vẫn chưa kết nối; giao diện xử lý xung đột vẫn chỉ minh họa và chưa ghi lựa chọn thật.

## Quy tắc vận hành

1. Các engine dùng cùng instance Room database chia sẻ một coordinator; một lượt chờ lượt trước, không gửi cạnh tranh. Không hỗ trợ đồng bộ đa process hoặc nhiều instance database trỏ cùng file; application hiện có dùng một database instance.
2. Mỗi lượt thử tối đa một request/task. Request chưa xác nhận được ưu tiên trước task pending mới; không tạo ID mới vì timeout, 5xx, mất phản hồi hoặc response sai. Không tồn tại trạng thái IN_FLIGHT khiến request bị bỏ quên sau khởi động lại.
3. API chạy ngoài transaction Room. Xác nhận task và xóa outbox cùng transaction; rollback nếu một bước thất bại. Response revision cũ cập nhật baseVersion nhưng giữ nguyên nội dung/revision mới và PENDING.
4. 422 chặn cho tới chỉnh sửa mới; vẫn đối chiếu được server thay đổi độc lập để lưu conflict/hội tụ. MUTATION_ID_REUSED và TASK_NOT_FOUND chặn, không tự thay ID. 429 có Retry-After giữ mốc không gửi trước hạn. Không có vòng retry tự động hoặc backoff scheduler ở bước này.
5. Pull phải là response 200 hợp lệ, đầy đủ từng object, không ID trùng. Validate cả danh sách trước transaction, không áp dụng một phần response sai. ID thiếu không mang nghĩa xóa; bản server cũ bị bỏ qua. Pull không được thay receipt cho request còn chưa rõ đã commit.
6. Hội tụ so business fields + trạng thái xóa, bỏ qua metadata/timestamp; hai tombstone là cùng ý định xóa. Xung đột giữ nguyên local/baseVersion và cả server snapshot, không chọn bên thắng hoặc tự ghi đè. Bản local tombstone không tự phục hồi bởi pull.
7. Task mới tạo rồi xóa, chắc chắn chưa có request, được bỏ cục bộ khi chuẩn bị sync; không ghi SYNCED giả. Nếu CREATE đã bắt đầu/chưa rõ kết quả, tiếp tục đúng CREATE cũ trước, rồi gửi DELETE dựa trên version được xác nhận.
8. Fake chỉ giữ trạng thái trong đời instance server test. Khi mô phỏng client được tạo lại, giữ nguyên fake và mở lại client database; không tuyên bố server giả có persistence sau restart server.

## Kiểm thử và bằng chứng

**Lượt cuối: BUILD SUCCESSFUL, 39 giây.** Các test cũ được giữ nguyên, không bỏ qua test.

| Lệnh/kiểm tra | Kết quả thực tế |
| --- | --- |
| `:app:assembleDebug` | Thành công, APK `android/app/build/outputs/apk/debug/app-debug.apk` |
| `:app:testDebugUnitTest` | **82 test**, 0 failures/errors/skipped: 36 SyncEngine mới và 46 test cũ |
| `:app:assembleDebugAndroidTest` | Thành công |
| `:app:connectedDebugAndroidTest` | **23 test**, 0 failures/errors/skipped: 11 RoomSyncStore/migration mới và 12 Room cũ, Medium_Phone / emulator-5556 / API 37 |
| `:app:lintDebug` | Thành công, **0 lỗi, 8 cảnh báo** cấu hình/phiên bản hiện có |
| `:app:compileReleaseKotlin` | Thành công; output release có **0** class của `sync/fake` / StatefulFakeTaskApi |
| Kiểm tra phạm vi | SHA-256 của 52 tệp UI/concept, hợp đồng, DOCX, schema v1 và cấu hình dependency/toolchain không đổi; DDL bảng tasks v2 giống v1; `git diff --check` sạch |

Lệnh cuối chạy trong `android/`, PowerShell:

```powershell
$env:JAVA_HOME='C:\Users\Khanh Ngo\.jdks\jbr-17.0.14'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir="' + (Join-Path (Get-Location).Path '.gradle\tmp') + '"'
$env:ANDROID_SERIAL='emulator-5556'
.\gradlew.bat --offline --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:connectedDebugAndroidTest :app:lintDebug :app:compileReleaseKotlin '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

Giữ JVM criteria đã có; không nâng hoặc tải toolchain. Không tải SDK/system image: khởi động AVD Medium_Phone đã có bằng `-no-snapshot-load -no-snapshot-save -feature -Vulkan -memory 3072`, giữ userdata. Cảnh báo `sun.misc.Unsafe` từ protobuf của công cụ test không làm test thất bại; không sửa thư viện để che cảnh báo.

Bằng chứng local, Git bỏ qua:

- Unit XML/HTML: `android/app/build/test-results/testDebugUnitTest/`, `android/app/build/reports/tests/testDebugUnitTest/index.html`.
- Test thiết bị: `android/app/build/outputs/androidTest-results/connected/debug/TEST-Medium_Phone(AVD) - 17.xml` và `android/app/build/reports/androidTests/connected/debug/index.html`.
- Lint: `android/app/build/reports/lint-results-debug.html` / `.txt`.
- Hash đối chiếu phạm vi và bản tổng hợp kết quả: `android/app/build/verification/phase5/`.

| Bất biến | Test kiểm chứng |
| --- | --- |
| CREATE → UPDATE → DELETE, đúng method/path/baseVersion, version server | `createUpdateDeleteUseExactContractAndServerVersions` |
| Import task/tombstone; active Flow loại tombstone | `pullImportsNewTasksAndTombstonesWithoutPendingOrFakeWrites`, `pullStoresNewServerTaskAndTombstoneButActiveFlowHidesDeletedTask` |
| Chỉ server sửa; hai bên sửa khác; cùng nội dung; cùng ý định xóa | `serverOnlyEditUpdatesCleanClientWithoutConflict`, `independentEditsPersistBothSnapshotsAndBlockOnlyThatTask`, hai test hội tụ |
| Receipt trước version/validation; khác request cùng mutationId bị từ chối | `successfulReceiptRejectsReusedIdWithDifferentRequestBeforeValidation` |
| Timeout trước commit; commit rồi mất phản hồi; retry DELETE không tăng version lần nữa | `timeoutBeforeCommitRetriesExactRequestOncePerExplicitRun`, `lostCommitResponseResumesReceiptBeforeVersionEvenAfterRemoteEdit`, `lostDeleteResponseRetriesReceiptWithoutIncreasingVersionAgain` |
| Sửa/toggle/xóa trong lúc gửi không bị response cũ ghi đè | Ba test `*DuringSend*` ở unit và ba test `room*During*` dùng Room thật; gate được giữ trong khi repository hoàn thành ghi |
| Cancellation trước/sau commit truyền lại; engine mới tiếp tục request cũ | Hai test `cancellation*Commit*`; test Room transaction cancellation |
| Đóng/mở lại file giữ outbox, hai snapshot conflict, Retry-After | `durableOutboxResumesSameRequestAfterDatabaseAndEngineReopen`, `conflictSnapshotsSurviveReopenAndBlockEditingOnlyTheirTask`, `retryAfterSurvivesDatabaseReopenAndDoesNotSendEarly` |
| Hai engine không chạy cạnh tranh | `separateEnginesSharingDatabaseSerializeWholeRuns`, `twoRoomStoreInstancesShareOneRunCoordinator` |
| Task mới tạo/xóa chưa gửi; xóa khi CREATE chưa rõ kết quả | `newCreateThenDeleteIsRemovedOnlyWhenProvenUnsent`, `deleteAfterUncertainCreateMustFirstRetryTheOriginalCreate` |
| 422 không retry vô hạn, sửa mới mới tạo request; ID reused không bị che | `validationBlocksUntilUserEditWithoutInfiniteRetry`, `mutationIdReusedBlocksWithoutHidingItWithNewId` |
| Task bị 422 vẫn phát hiện conflict/hội tụ khi server đổi độc lập | Hai test `validationBlockedTask*` |
| Response sai/thiếu không xác nhận, không áp dụng pull một phần | `malformedSuccessCannotBeAcknowledgedByMatchingPull`, `invalidAcknowledgementIdVersionOrBusinessValuesPreserveOutbox`, `invalidOrIncompletePullDoesNotPartiallyApplyOrEraseLocalTasks`, các test lỗi conflict/validation sai task |
| Pull cũ/ID thiếu/tombstone không phục hồi | `olderPullAndMissingIdsNeverOverwriteOrDeleteNewerLocalData`, `cleanTombstoneIsNotAutomaticallyRestoredByPull` |
| Xóa–sửa hai chiều giữ snapshot, không âm thầm bỏ sửa | `independentDeleteVersusEditKeepsLocalTombstoneAndRemoteSnapshot`, `remoteDeleteVersusLocalEditNeverSilentlyDropsTheEdit` |
| SQLite lỗi giữa xác nhận và xóa outbox rollback cả hai | `acknowledgementAndOutboxRemovalRollBackTogetherOnSQLiteFailure`; retry nhận receipt, server chỉ commit một lần |
| Migration v1 bảo toàn task active, tombstone, pending/synced và mọi metadata | `migrationFromRealV1PreservesEveryTaskColumnAndTombstone`; mở fixture v1 qua Room v2 và generated schema validation |

Unit test dùng `runTest` và cùng scheduler; gate CompletableDeferred điều phối chính xác, Retry-After dùng thời gian ảo. Test thiết bị dùng coroutine/gate và Room thật, không sleep hoặc dựa vào log để assert.

Mọi test thiết bị chỉ mở Room in-memory hoặc file `studyflow-sync-test-<UUID>.db` / `studyflow-test-<UUID>.db`, tự đóng và dọn đúng các file đó. Không mở hoặc xóa `studyflow.db`, không clear data/uninstall/reset dữ liệu để vượt migration. Không dùng fake để đánh dấu task production SYNCED.

Lần chạy thiết bị đầu có một lỗi ở fixture migration (13 placeholder INSERT trong khi bảng v1 có 12 cột); đã sửa thành 12, không sửa migration để né lỗi. Sau đó đã chạy lại toàn bộ. Output build/test/log nằm trong `android/app/build/`, Git bỏ qua.

## Giới hạn và bước tiếp theo

Không có UI mới nên không tạo ảnh concept mới cho bước 5. Không nối server thật, chưa thử hai thiết bị thật, HTTP/JSON thực tế, server restart bền vững, thiết bị vật lý/API 26 hoặc OS kill process thật. Việc tạo lại engine/database và mất phản hồi được mô phỏng xác định trong test; không gọi đó là kiểm tra process kill trên thiết bị.

Chưa triển khai hành động giải quyết conflict thật, tự động nền, backoff scheduler hoặc đồng bộ tăng dần — đúng phạm vi yêu cầu. Bước tiếp theo: adapter Retrofit nối backend theo cùng hợp đồng, sau đó luồng xác nhận conflict có kiểm tra lại version và scheduler khi được yêu cầu.

Không thay đổi trạng thái hoặc đóng nội dung chưa lưu của ứng dụng khác. Chỉ tắt Windows sau khi kiểm tra cuối thành công, báo cáo đã lưu/gửi và các tác vụ do agent chạy đã kết thúc; dùng shutdown thường, không force.

Kiểm tra trước shutdown: các lệnh Gradle đã trả exit code 0; không còn tiến trình GradleWrapperMain/GradleDaemon/GradleWorkerMain khi kiểm tra process. Test đã đóng database; không còn file database có tiền tố test. AVD do nhiệm vụ khởi chạy đã nhận lệnh đóng bình thường và phản hồi OK. Tất cả mã, schema, README, báo cáo và bản tổng hợp kết quả đã lưu xuống đĩa; không có phần triển khai bị chặn cần người dùng quyết định. Theo ủy quyền trong yêu cầu, sau khi gửi báo cáo hoàn tất trong chat sẽ yêu cầu shutdown Windows bằng `shutdown.exe /s /t 0`, không dùng `/f` hoặc thời gian chờ khiến force được bật ngầm. Nếu Windows chặn do ứng dụng chưa lưu, giữ nguyên để người dùng xử lý.
