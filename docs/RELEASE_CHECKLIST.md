# Release checklist

## Mã nguồn và dữ liệu

- [ ] `python3 scripts/validate_release.py` đạt.
- [ ] `python3 scripts/check_milestone4_complete.py` đạt.
- [ ] Tất cả unit test và migration test đạt.
- [ ] Room schema JSON được commit và khớp version 13.
- [ ] Backup format 1–10 được kiểm tra nhập ngược.
- [ ] Không có private key, API key, cookie, token, prompt AI hoặc response AI trong artifact/backup.

## Build

- [ ] JDK 17 và Android SDK Platform 36.
- [ ] `testDebugUnitTest` đạt, gồm media-button soak, Sonic/loudness/selector và MP3 `CHAP/CTOC`.
- [ ] Room migration instrumentation `9 → 10 → 11 → 12 → 13` đạt.
- [ ] `lintDebug` không có lỗi chặn.
- [ ] `assembleDebug` và `assembleDebugAndroidTest` đạt.
- [ ] `connectedDebugAndroidTest` đạt trên emulator API 33 trở lên.
- [ ] `:app:verifyReleaseSigning bundleRelease` đạt với khóa production.
- [ ] SHA-256 của AAB, APK kiểm thử và mapping file được lưu trong hồ sơ phát hành.

## TTS, tai nghe và AI

- [ ] Một/hai/ba lần bấm, nhấn giữ và transport key đúng trên Android 13–15.
- [ ] Không nhận lệnh trùng khi receiver và MediaSession cùng chuyển sự kiện.
- [ ] Tai nghe dây và ít nhất ba thiết bị Bluetooth được kiểm tra.
- [ ] Cuộc gọi, báo thức, audio focus loss và đổi output không làm kẹt playback.
- [ ] Engine riêng theo vai fallback đúng khi engine/voice không tồn tại.
- [ ] Biểu cảm và Sonic không gây clipping, crash hoặc lệch checkpoint.
- [ ] Quota AI, retry/backoff, `Retry-After`, consent và fallback offline hoạt động đúng.
- [ ] Usage counter không chứa nội dung chương hoặc phản hồi AI.

## Nhạc và audiobook

- [ ] Scene music decode/mix/fade/loop hoạt động với URI WAV, M4A và MP3 hợp lệ.

- [ ] Playlist tuần tự, shuffle và smart avoid-repeat hoạt động đúng.
- [ ] Loudness target không làm nhạc át narration; ducking/crossfade/continuity không rò MediaPlayer.
- [ ] WAV/M4A/MP3 phát được trên ít nhất hai trình phát.
- [ ] Split-per-chapter, chapter marker và resume checkpoint đúng.
- [ ] TTS/export liên tục nhiều giờ không ANR, crash hoặc phình vùng tạm.

## Accessibility, pháp lý và phát hành

- [ ] TalkBack và font lớn dùng được màn hình Cá nhân, voice-role và xuất audiobook.
- [ ] `THIRD_PARTY_NOTICES.md` có trong gói phát hành.
- [ ] Tuân thủ LGPL của LAME/java-lame và libmobi notice.
- [ ] Chính sách quyền riêng tư mô tả AI online, quota cục bộ và dữ liệu không backup.
- [ ] Release notes ghi rõ ảnh bìa không được hỗ trợ theo phạm vi dự án.
