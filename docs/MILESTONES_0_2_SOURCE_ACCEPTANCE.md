# Nghiệm thu phía mã nguồn cho Mốc 0, 1 và 2

Ngày chốt: 2026-08-05

## Phạm vi nghiệm thu

Tài liệu này tách hai lớp bằng chứng:

- **SOURCE COMPLETE**: mã, contract, migration, fixture và gate offline có thể kiểm tra trong kho nguồn đã hoàn thành.
- **RUNTIME DEFERRED**: Gradle Android, APK/AAB, emulator, thiết bị thật và kiểm tra website trực tiếp được chủ dự án chủ động hoãn đến sau Mốc 9.

`RUNTIME DEFERRED` không được dùng để che một lỗi mã nguồn đã biết. Nếu gate offline phát hiện lỗi, lỗi phải được sửa trước khi đóng phần source-side.

## Mốc 0: Build contract và đường chuẩn

Trạng thái phía mã nguồn: **SOURCE COMPLETE**.

- JDK 17, SDK 36 và Build Tools 36.0.0 được khóa bằng preflight.
- Một cổng duy nhất `scripts/m0_gate.sh`/`.ps1` điều phối static gate, test, lint, APK và AAB.
- CI gọi đúng cổng Mốc 0, không duy trì danh sách Gradle task trùng lặp.
- Gradle Wrapper bootstrap có checksum, giới hạn kích thước và ghi file nguyên tử.
- Room migration contract và schema mặc định có gate SQLite offline.
- Evidence collector tạo hash cho artifact khi môi trường Android sẵn sàng.

Các module JVM của nền tảng nguồn đã được biên dịch tách module và smoke test PASS.

Hoãn theo quyết định chủ dự án: dependency resolution Gradle Android, Compose/Room compiler, lint Android, APK, AAB, instrumentation và thiết bị thật.

## Mốc 1: Lõi đọc truyện

Trạng thái phía mã nguồn: **SOURCE COMPLETE**.

- Hai chế độ đọc, tìm trong chương, bookmark, ghi chú và cài đặt hiển thị giữ nguyên vì các gate hiện tại PASS.
- Chỉ số đoạn người dùng tách khỏi các mảnh TTS, tránh lệch bookmark/progress khi đoạn dài bị chia.
- Vị trí bắt đầu được chọn theo thứ tự cưỡng bức, tiến độ cùng chương, rồi 0; luôn clamp theo nội dung hiện tại.
- Chương rỗng bị chặn trước khi mở Reader hoặc phục hồi playback.
- Điều hướng dùng vị trí thực trong mục lục, không giả định `chapter.index` liên tục.
- Tác vụ cũ bị hủy và không thể ghi đè truyện/chương mới.
- Chính sách phím âm lượng chỉ bắt sự kiện ở Reader, khi tùy chọn bật, ACTION_DOWN đầu tiên; ngoài Reader trả lại hệ thống.
- Gate executable bao phủ Unicode, đoạn 7.200 ký tự, mục lục 10.000 chương và chỉ mục không liên tục.

Hoãn: Compose instrumentation, process death thật, reboot và kiểm tra accessibility trên thiết bị.

## Mốc 2: Nguồn, bình luận và extension

Trạng thái phía mã nguồn: **SOURCE COMPLETE**.

- Giữ nguyên bảy SourcePack đã port và hệ chữ ký/rollback vì fixture và gate đang PASS.
- Bình luận native hỗ trợ payload nhúng và action `COMMENTS`.
- Parser chuẩn hóa alias trường, lọc control character, giới hạn 100 mục/trang và 20.000 ký tự/nội dung.
- Hỗ trợ phân trang qua `nextPageUrl`, `nextUrl`, `next` hoặc `cursor`.
- Cache bình luận LRU có TTL, giới hạn 32 truyện và tối đa 500 bình luận/truyện; không lưu cookie/credential.
- Gộp trang có khử trùng lặp; UI hiển thị nguồn cache và nút tải thêm.
- Kết quả cũ bị loại khi người dùng đổi truyện.
- Fixture hai trang kiểm tra alias, cursor kết thúc và khử trùng lặp.
- Công cụ kiểm kê XPK cũ chỉ phân tích tĩnh, không thực thi Lua/DEX/native; cả bảy nguồn cũ đều có port tương ứng.
- Không bật capability COMMENTS cho nguồn cụ thể nếu chưa có fixture nguồn đó. Đây là cơ chế tránh tuyên bố hỗ trợ sai.

Các cổng compiler nền tảng nguồn đã PASS sau khi tách theo module, gồm package verification, rollback, declarative runtime, public-address policy và repository signature.

Hoãn: live-site verification, cookie/login/WebView thực, ma trận plugin bên thứ ba và thử trên Android.

## Luật chống hồi quy

1. Không viết lại chức năng đang PASS chỉ để đổi kiến trúc hoặc tên gọi.
2. Mọi thay đổi phải thêm hoặc giữ nguyên gate liên quan.
3. Chỉ sửa khu vực đã chứng minh có lỗi hoặc còn thiếu contract.
4. Nếu tiêu chí kiểm tra lỗi thời vì kiến trúc hợp lệ đã thay đổi, sửa gate để kiểm tra hành vi mới, không đảo ngược chức năng.
5. Mốc 3 phải chạy lại gate tổng Mốc 0–2 trước khi bàn giao.
