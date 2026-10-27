# StudyFlow

Bộ khung dự án quản lý công việc học tập, gồm ứng dụng Android, server và hợp đồng đồng bộ dùng chung.

## Cấu trúc

| Thư mục | Nội dung |
| --- | --- |
| `android/` | Dự án Android Studio; chia lớp data, domain, UI và sync |
| `server/` | Backend Node.js; routes, controllers, services và repositories |
| `contracts/` | OpenAPI, giao thức đồng bộ và JSON ví dụ |
| `docs/` | Yêu cầu, thiết kế, kế hoạch, kiểm thử, báo cáo và demo |
| `releases/` | Liên kết APK, video và báo cáo cuối |

## Trạng thái

Đã khởi tạo cấu trúc thư mục. Các tệp mã nguồn, cấu hình build và tài liệu là placeholder để triển khai tiếp; ứng dụng và server chưa chạy được. Chưa chọn phiên bản Gradle, Android Gradle Plugin, Kotlin hoặc thư viện. Gradle Wrapper chưa được sinh, các script hiện tại chỉ thông báo bước thiết lập cần thực hiện.

Xem [hướng dẫn thiết lập](docs/setup.md), [kiến trúc](docs/architecture.md) và [kế hoạch](docs/project-plan.md).

Các thư mục trống có `.gitkeep` để được lưu trong Git. `server/storage/` là dữ liệu runtime và bị bỏ qua. Không commit `.env`, khóa ký hoặc thông tin đăng nhập.
