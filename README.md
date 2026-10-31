# StudyFlow

Ứng dụng Android quản lý công việc học tập theo hướng offline, dùng chung backend và hợp đồng đồng bộ. Không có tài khoản; các thiết bị dùng cùng bộ dữ liệu server.

## Cấu trúc

| Thư mục | Nội dung |
| --- | --- |
| `android/` | Dự án Android Studio; chia lớp data, domain, UI và sync |
| `server/` | Backend Node.js; routes, controllers, services và repositories |
| `contracts/` | OpenAPI, giao thức đồng bộ và JSON ví dụ |
| `docs/` | Yêu cầu, thiết kế, kế hoạch, kiểm thử, báo cáo và demo |
| `releases/` | Kiểm kê sản phẩm nộp và liên kết đã xác minh |

## Trạng thái

Backend đã có sáu endpoint, SQLite migration/seed, validation, version conflict, xóa logic, receipt chống áp dụng trùng, log và công cụ tái hiện lỗi. Xem [README server](server/README.md) để cài, chạy và kiểm thử; [kết quả](docs/testing/test-results.md) ghi riêng từng lượt thực hiện và giới hạn.

Android đã có Wrapper, UI/Room và phần lõi đồng bộ độc lập; trạng thái chi tiết do A quản lý trong [README Android](android/README.md). Audit bàn giao Bước 11 chưa có HTTP adapter/binding vào app, scheduler/worker/resolver hoàn chỉnh hoặc bằng chứng hai thiết bị. Không suy ra tích hợp Android đã đạt từ test backend. Bước 12 chưa có số đo; chưa xác minh được APK/video/báo cáo cuối để nộp.

## Bắt đầu với backend

Người nhận có thể lấy nhánh backend vào **thư mục mới**:

```bash
git clone --branch codex/backend-api-contract --single-branch https://github.com/KhanhNgoNG/StudyFlow.git StudyFlow
cd StudyFlow/server
node --version
npm --version
npm ci
if [ ! -f .env ]; then cp .env.example .env; fi
npm run db:migrate
npm run db:seed
npm run db:inspect
npm start
```

Dùng Node `>=22.14.0 <23`; lượt bàn giao kiểm tra Node 22.14.0/npm 11.6.4. Seed là tùy chọn trên DB mới. Kiểm tra cấu hình trước khi chạy với dữ liệu đang dùng; xem [biến môi trường và lỗi thường gặp](server/README.md). Terminal khác gọi `curl -i http://127.0.0.1:3000/api/v1/health`; dừng server bằng Ctrl+C trong terminal của nó. `npm test`, `npm run test:smoke`, `npm run test:faults` chạy từ `server/`, tự dùng DB tạm riêng.

Lượt tái hiện Bước 13 ngày 30/10/2026 dùng bản sao có kiểm soát từ HEAD `da465da`, **bao gồm tài liệu Bước 11/13 chưa commit tại thời điểm thử**. Các kết quả đó không được mô tả là chạy chỉ từ commit `da465da`. Hướng dẫn clone ở trên lấy nhánh backend; dùng `git log -1` để ghi nhận commit thực tế người nhận đang dùng. Bước rà soát ngày 31/10/2026 chuẩn bị lưu tài liệu này theo yêu cầu commit/push riêng, không chạy lại benchmark hoặc thay kết quả cũ.

## Đọc tài liệu theo nhu cầu

| Nhu cầu | Tài liệu |
|---|---|
| Chạy server, ghép Android, dừng/dọn môi trường riêng | [Server](server/README.md), [setup](docs/setup.md) |
| Hiểu API, payload, replay và xung đột | [Sync Protocol](contracts/sync-protocol.md), [OpenAPI](contracts/openapi.yaml), [JSON mẫu](contracts/examples/README.md) |
| Hiểu các lớp và transaction | [Kiến trúc](docs/architecture.md), [database](docs/database-design.md) |
| Xem yêu cầu, tiến độ và việc còn lại | [Yêu cầu](docs/requirements.md), [kế hoạch](docs/project-plan.md) |
| Chạy lại và đánh giá bằng chứng | [Ca kiểm thử](docs/testing/test-cases.md), [kết quả](docs/testing/test-results.md), [demo](docs/demo/demo-script.md) |
| Tổng hợp báo cáo và D1–D8 | [Nội dung backend trong báo cáo](docs/report/outline.md), [sản phẩm nộp](releases/README.md), [nguồn](docs/references.md) |

Lưu lockfile, migration, seed, hợp đồng và test trong Git. `.env`, `node_modules`, database/WAL/SHM runtime, build output và log cá nhân bị loại trừ; không gửi chúng như mã nguồn bàn giao.
