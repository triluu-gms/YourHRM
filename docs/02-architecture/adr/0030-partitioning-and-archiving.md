# ADR-0030: Partitioning, vòng đời dữ liệu và lưu trữ

- **Trạng thái:** Đã chấp thuận (2026-10-16, D-03)
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, Solution architect, DevOps
- **Liên quan:** NFR-SAAS-004/005, NFR-ATT-001/002/007, NFR-PAY-003, BR-PAY-123, NFR-REC-009; ADR-0003 (pool cluster ~100k nhân viên), ADR-0028, ADR-0029; [DESIGN.md](../../../database/postgresql/DESIGN.md) §10

## Bối cảnh

Ở kịch bản định cỡ (500 tenant, 1 triệu nhân viên trên khoảng 10 pool cluster), các bảng lớn mỗi năm là: dữ liệu chấm công thô 1,25 tỷ dòng, bảng công hằng ngày 360 triệu, dòng kết quả bảng lương 864 triệu, audit event 480 triệu, và lưu lượng outbox khoảng 700 triệu (tạm thời). Thời hạn lưu theo luật tới 10 năm `[verify-legal]`, nhưng truy vấn tập trung vào 1–3 năm gần nhất. Xoá hàng loạt và vacuum không được ảnh hưởng tới cao điểm tính lương (ngày 25–5).

## Quyết định

1. **Partition theo thời gian, không theo tenant.** Partition theo khoảng hằng tháng cho `att.raw_punch` (`work_date_hint`), `att.daily_timesheet` (`work_date`), `att.attendance_result_line`, `pay.result_line` (`period_month`), `audit.audit_event` (`occurred_at`) và `saas.usage_record`; hằng năm cho `pay.run_input_snapshot`; hằng ngày cho `*.outbox_event`; hằng tháng cho `*.inbox_message`. Tenant đủ lớn để cần partition riêng thuộc về tier riêng hoặc pool cluster riêng của nó.
2. **Cửa sổ nóng trong OLTP:** dữ liệu chấm công 25 tháng, bảng công 37, dòng kết quả 36 (sau đó nén lại, ADR-0029), audit 24, outbox 7 ngày, inbox 90 ngày. Kết quả chấm công, snapshot, BHXH/thuế TNCN và tờ khai được giữ 10 năm.
3. **Luồng lưu trữ:** `DETACH PARTITION … CONCURRENTLY` → xuất ra Parquet theo thư mục từng tenant, mã hoá bằng tenant KEK, trong object storage tại VN có object lock → `saas.archive_manifest` (URI, số dòng, sha256) → `DROP`. Khôi phục = attach lại vào một schema tạm theo yêu cầu (mục tiêu ≤ 2 ngày làm việc; ngưỡng và SLA đã được chấp thuận, D-03).
4. **Quản lý partition:** pg_partman nếu có trên image CNPG, nếu không thì dùng `sys.create_range_partitions()` chạy bởi scheduler, tạo trước 3 kỳ. Không có partition `DEFAULT` trên các bảng nóng.
5. **Cấu hình vacuum:** bảng chỉ-insert tinh chỉnh `autovacuum_vacuum_insert_scale_factor` và được freeze khi chốt tháng. Bảng update nhiều (`daily_timesheet`, `ot_cap_ledger`, `saas.job`) dùng `fillfactor = 80` và scale factor thấp.
6. **Quy tắc index:** `tenant_id` đứng đầu mọi index; partial index cho các dòng hiện hành/đang hoạt động; mọi FK đều có index; GIN trigram cho tìm kiếm tên không dấu.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Hash partitioning theo tenant | Không giúp cho việc lưu trữ (vẫn cần `DELETE`), và việc cắt tỉa truy vấn chỉ giúp truy vấn một tenant, vốn đã được btree trên `tenant_id` phục vụ. |
| List partition theo tenant | 500+ tenant × các bảng lớn nghĩa là hàng nghìn partition; overhead khi lập kế hoạch truy vấn; DDL khởi tạo theo từng tenant. |
| Giữ tất cả online | Tăng không giới hạn về vacuum, backup và thời gian khôi phục (RTO 4 h) cho dữ liệu hiếm khi được đọc. |
| Citus / PostgreSQL phân tán | Độ phức tạp vận hành không xứng đáng ở mức khoảng 100k nhân viên mỗi cluster; thay vào đó scale-out bằng pool cluster. |

## Hệ quả

- \+ Lưu trữ là một thao tác metadata. Backup, khôi phục và vacuum có giới hạn theo từng cluster.
- − Dữ liệu đã lưu trữ cần một bước khôi phục (vài phút đến vài giờ); yêu cầu DSR "truy cập" có thể cần đến bước này.
- − Tạo trước partition là một phụ thuộc được giám sát.
- − Số liệu khối lượng là ước tính **[spike: load test SP-2 trên bộ dữ liệu tham chiếu]**.
