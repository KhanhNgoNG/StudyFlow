# Thiết lập môi trường

Repo hiện là bộ khung, chưa build hoặc chạy ứng dụng được.

## Android

1. Chọn bộ phiên bản tương thích cho JDK, Gradle, Android Gradle Plugin và Kotlin.
2. Hoàn thiện `android/settings.gradle.kts`, các tệp build và version catalog.
3. Sinh Gradle Wrapper chính thức, thay hai script placeholder và thêm JAR/properties vào `android/gradle/wrapper/`.
4. Mở `android/` trong Android Studio, cấu hình Android SDK trong `local.properties`.
5. Triển khai Application, Activity, giao diện và các lớp dữ liệu trước khi build.

## Server

1. Chọn phiên bản Node.js, framework HTTP và cơ sở dữ liệu.
2. Thêm dependencies, scripts và cập nhật lockfile bằng npm.
3. Sao chép `server/.env.example` thành `server/.env` và nối cấu hình vào mã nguồn.
4. Tạo `server/storage/` nếu chưa tồn tại; thư mục này không được Git theo dõi.
5. Triển khai migrations, routes và kiểm thử trước khi chạy server.

## Hợp đồng chung

Định nghĩa OpenAPI và giao thức đồng bộ trong `contracts/` trước khi triển khai API hai phía. JSON ví dụ hiện chưa có dữ liệu mẫu.
