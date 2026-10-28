# StudyFlow Server

Backend của StudyFlow sử dụng Node.js, JavaScript ES modules và Express 5. Hiện mã nguồn có cấu hình môi trường, script khởi động và endpoint health. Hợp đồng API dùng chung với Android nằm trong [giao thức đồng bộ](../contracts/sync-protocol.md) và [OpenAPI](../contracts/openapi.yaml).

## Yêu cầu môi trường

- Node.js `>=22.14.0 <23`, theo `package.json`.
- npm đi kèm Node.js để cài dependency từ `package-lock.json`.

Kiểm tra phiên bản:

```bash
node --version
npm --version
```

## Cài đặt

Từ thư mục gốc repository:

```bash
cd server
npm ci
```

Khi thiết lập lần đầu, tạo cấu hình local:

```bash
cp .env.example .env
```

Nếu đã có `.env`, giữ cấu hình hiện tại và chỉ bổ sung biến còn thiếu từ `.env.example`. Các lệnh chạy bên dưới được thực hiện trong thư mục `server/`.

## Cấu hình

| Biến | Giá trị mẫu | Ý nghĩa |
|---|---|---|
| `NODE_ENV` | `development` | Tên môi trường chạy; hiện chưa có hành vi riêng theo môi trường. |
| `HOST` | `0.0.0.0` | Lắng nghe trên các giao diện mạng của máy; dùng `127.0.0.1` nếu chỉ cần truy cập từ máy đó. |
| `PORT` | `3000` | Cổng HTTP, phải là số nguyên từ 1 đến 65535. |
| `DATABASE_PATH` | `./storage/studyflow.db` | Đường dẫn dự kiến cho SQLite; hiện chưa được dùng để mở hoặc tạo database. |

Script chạy dùng `--env-file=.env` của Node.js để đọc cấu hình trước khi nạp ứng dụng. File `.env` phải tồn tại; biến đã được đặt trong môi trường shell được ưu tiên hơn giá trị trong file. Sau khi thay `.env`, dừng và khởi động lại server.

## Chạy server

Chế độ phát triển, tự khởi động lại khi file JavaScript đang được sử dụng thay đổi:

```bash
npm run dev
```

Chạy thông thường:

```bash
npm start
```

Với cấu hình mẫu, server lắng nghe ở cổng `3000`. Nhấn `Ctrl+C` trong terminal đang chạy để dừng server.

## Kiểm tra kết nối

Giữ server chạy và mở terminal thứ hai:

```bash
curl -i http://localhost:3000/api/v1/health
```

Kết quả mong đợi là HTTP `200 OK` với body rỗng. Endpoint hiện chỉ chứng minh tiến trình HTTP phản hồi được; chưa kiểm tra database và không chứng minh đồng bộ đã hoạt động. Đây là hướng dẫn kiểm tra, không phải kết quả kiểm thử đã ghi nhận.

Nếu đổi `PORT`, thay cổng tương ứng trong URL.

## Cấu trúc mã hiện tại

- `src/app.js`: export `createApp()`, cấu hình Express, đọc JSON và khai báo health; không tự mở port.
- `src/server.js`: tạo app, mở port và xử lý lỗi khởi động.
- `src/config/env.js`: đọc cấu hình và kiểm tra `PORT`.
- `.env.example`: cấu hình mẫu được lưu trong Git.
- `package-lock.json`: khóa phiên bản dependency để cài lại bằng `npm ci`.

## Giới hạn hiện tại

- Chưa kết nối SQLite, chưa có migration hoặc seed hoạt động.
- Chưa triển khai API công việc: danh sách, chi tiết, tạo, cập nhật và xóa logic.
- Chưa có validation task, kiểm tra version, tombstone hoặc receipt chống áp dụng trùng.
- Chưa có middleware lỗi chung theo hợp đồng; lỗi route và JSON hiện theo hành vi mặc định của Express.
- Chưa có script test hoặc bộ integration test backend.

Các endpoint và schema trong OpenAPI mô tả hợp đồng cần triển khai; không có nghĩa mọi endpoint đã hoạt động. Không dùng server hiện tại để xác nhận lưu bền vững hoặc đồng bộ thật.

## Xử lý lỗi khởi động

- Thiếu `.env`: tạo file từ `.env.example` theo hướng dẫn cài đặt.
- `PORT` không hợp lệ: sửa thành số nguyên từ 1 đến 65535 rồi chạy lại.
- Cổng đang được sử dụng (`EADDRINUSE`): dừng tiến trình server cũ hoặc chọn cổng khác trong `.env`.
- Không tìm thấy Express: chạy `npm ci` trong `server/` và kiểm tra lại phiên bản Node.js.

## Quy tắc lưu Git

Lưu mã nguồn, README, `.env.example`, `package.json` và `package-lock.json`. Không lưu `.env`, `node_modules/`, database runtime trong `storage/` hoặc log cá nhân; các đường dẫn này đã được loại trừ trong `.gitignore` của repo.
