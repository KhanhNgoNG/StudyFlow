# Tài liệu tham khảo

Các nguồn dưới đây được đối chiếu ngày 30/10/2026. Tài liệu thư viện giải thích cơ chế; quy tắc API/version/replay là quyết định của StudyFlow trong [Sync Protocol](../contracts/sync-protocol.md), không gán cho thư viện. Phiên bản thực tế lấy từ `server/package-lock.json` và lượt tái hiện, không chọn phiên bản mới nhất trên website.

| Nguồn chính thức | Nội dung sử dụng và giới hạn |
|---|---|
| [Node.js v22.14.0 CLI](https://nodejs.org/download/release/v22.14.0/docs/api/cli.html) | ES modules/CLI `--env-file`, `--watch`; script hiện tại dùng Node 22, không dùng API từ Node mới hơn |
| [npm CLI v11: npm ci](https://docs.npmjs.com/cli/v11/commands/npm-ci/) | Cài theo lockfile; package/lock phải khớp, không cập nhật lock như npm install. Lượt sạch dùng npm 11.6.4 |
| [Express 5 API](https://expressjs.com/en/5x/api/) | Router, JSON parser, request/response và middleware; dependency lock là Express 5.3.0 |
| [better-sqlite3 v12.11.1 API](https://github.com/WiseLibs/better-sqlite3/blob/v12.11.1/docs/api.md) | Connection, prepared statement, transaction đồng bộ và immediate; không kéo dài transaction qua await |
| [SQLite transactions](https://www.sqlite.org/lang_transaction.html) | BEGIN IMMEDIATE, COMMIT/ROLLBACK, một writer và SQLITE_BUSY; cơ sở cho contention + retry, không phải bằng chứng hiệu năng |
| [SQLite WAL](https://www.sqlite.org/wal.html) | WAL/SHM và concurrent reader/writer; WAL vẫn có giới hạn writer, không thay backup/test mất điện |
| [OpenAPI 3.0.3](https://spec.openapis.org/oas/v3.0.3) | Định dạng contract hiện tại. Test trong repo chỉ kiểm subset đã mô tả, không chứng nhận tuân thủ toàn bộ spec |
| [Android: offline-first data layer](https://developer.android.com/topic/architecture/data-layer/offline-first) | Nguồn local cho UI, queue/retry và đối chiếu local/network cho phần lý thuyết; không chứng minh app StudyFlow đã tích hợp |
| [Android: define WorkRequests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) | Constraints, retry/backoff và thời điểm chạy phụ thuộc hệ thống; phiên bản dependency/triển khai thật cần A xác nhận, không hứa chạy tức thì |
| [Android Emulator networking](https://developer.android.com/studio/run/emulator-networking-address), [network security config](https://developer.android.com/privacy-and-security/security-config) | URL host từ emulator và cấu hình debug HTTP trong hướng dẫn ghép thử; không xác nhận đã có request từ thiết bị |

Căn cứ nội bộ: [đặc tả dự án DOCX](../StudyFlow_Dac_ta_va_ke_hoach_du_an.docx) (tài liệu thiết kế đã có, không sửa trong Bước 13), [AGENTS.md](../AGENTS.md), source/tests, hợp đồng và [kết quả kiểm thử](testing/test-results.md). DOCX dẫn tới đề gốc PDF 6 trang; PDF chưa có trong repo nên chưa kiểm chứng trực tiếp yêu cầu nộp của đề. A/cả nhóm cần bổ sung đối chiếu đó trước khi chốt D1–D8. Không tạo hoặc bịa trích dẫn cho PDF chưa đọc.
