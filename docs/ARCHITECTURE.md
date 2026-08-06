# Kiến trúc Kotlin 1.1, lõi đọc và Source Platform 2

## Source Platform 2 foundation

```text
.ntsource
   ↓ SourcePackArchiveVerifier
strict manifest + hash coverage + signature trust
   ↓ SourceFixtureRunner
mandatory offline self-test
   ↓ permission diff + user approval
SourcePackStore: stage → tree-integrity → atomic activate/rollback
   ↓
DeclarativeSourceRuntime → SourcePackStorySource → SourceRegistry
   ↘ BoundedDiagnosticRecorder → redacted JSON export
```

Module boundaries:

- `source-api` không phụ thuộc Android hoặc network implementation.
- `source-package` chỉ xử lý trust/package và không kích hoạt source.
- `source-store` không cho phiên bản chưa stage trở thành active.
- `source-runtime` chỉ đọc resource trong gói và tiêu thụ budget.
- `source-diagnostics` không lưu secret thô.
- `app/sourceplatform` là composition root và bridge sang UI/`StorySource`.

Các nguồn Kotlin hiện có vẫn được giữ trong quá trình chuyển đổi. SourcePack có cùng ID sẽ được ưu tiên; fallback hard-code chỉ hoạt động khi pack không có hoặc bị vô hiệu. Network/browser/vBook sẽ đi qua capability broker riêng ở giai đoạn tiếp theo, không được gọi Android API trực tiếp từ runtime.

## Nguyên tắc

1. **Clean-room theo hành vi:** tái hiện trải nghiệm và yêu cầu chức năng, không chạy hoặc nhúng runtime Lua cũ.
2. **Typed boundaries:** nguồn truyện, playback, AI, xuất audio, backup và nhập sách giao tiếp qua model/hợp đồng Kotlin rõ ràng.
3. **Offline-first:** nội dung tải, cache chương, tiến độ, VietPhrase, hồ sơ giọng và job nền nằm trong Room hoặc DataStore.
4. **Consent trước mạng:** nội dung chương chỉ được gửi tới AI online khi người dùng bật consent, bật AI, lưu API key và chủ động bấm tác vụ.
5. **Background đúng chuẩn Android:** TTS dùng foreground media service; tải truyện dùng WorkManager `dataSync`; xuất WAV/M4A dùng WorkManager `mediaProcessing`.
6. **Fail closed:** URL ngoài allowlist, phản hồi quá lớn, archive bất thường, API key thiếu hoặc dữ liệu AI sai giao thức đều trả lỗi có mã.
7. **Parser có bằng chứng:** adapter và protocol quan trọng có fixture hoặc test thuần không cần mạng.
8. **Extension có kiểm soát:** chỉ SourcePack đã ký, capability-bounded và self-test được kích hoạt; không Lua, DEX/SO động hoặc Android API trực tiếp từ runtime.

## Luồng dữ liệu

```text
Compose UI
   ↓ action / StateFlow
AppViewModel
   ├── SourceRegistry → StorySource → HttpHtmlClient / SessionHttpClient
   ├── SourceHealthChecker → StorySource
   ├── EncryptedSourceSessionStore ↔ Android Keystore
   ├── EncryptedAiCredentialStore ↔ Android Keystore
   ├── OnlineAiServices → OpenAI-compatible HTTPS endpoint
   ├── VietPhraseProcessor / VietPhraseTransferManager
   ├── LibraryRepository → Room schema 7
   ├── SettingsRepository → DataStore
   ├── BackupTransferManager → SAF ZIP format 6
   ├── DownloadScheduler → ChapterDownloadWorker → Room
   ├── FollowingUpdateScheduler → FollowingUpdateWorker → SourceRegistry/Room
   ├── AudioExportScheduler → AudioExportWorker
   │      ├── Android TTS → WAV segment
   │      ├── AI/manual voice assignment → profile per paragraph
   │      ├── Pcm16WaveConverter → PCM16
   │      ├── WaveFileAssembler → WAV
   │      └── M4aAacEncoder → MediaCodec/MediaMuxer → M4A
   └── PlaybackQueueStore → ReaderPlaybackService
          ├── TtsVoiceCatalog / Android TTS engine
          ├── AI assignment or VoiceRoleResolver
          └── Scene cue timeline → local MediaPlayer track
```

## `sources`

Mỗi website là một `StorySource`. Adapter chịu trách nhiệm tìm kiếm, danh mục, chi tiết, mục lục phân trang, chương mới nhất và nội dung chương.

`HttpHtmlClient` và `SessionHttpClient` áp dụng HTTPS, allowlist trước request và tại từng redirect, tối đa 5 redirect, timeout, phản hồi tối đa 4 MiB, điều tiết theo host và cache bounded. Ứng dụng không tự vượt CAPTCHA, Cloudflare hoặc paywall.

Nguồn cần đăng nhập dùng `SessionHttpClient`. Cookie được mã hóa AES-GCM bằng khóa Android Keystore, ràng buộc AAD theo source ID. `SourceLoginActivity` là WebView đăng nhập cô lập, không JavaScript bridge, không file/content access và không phải runtime browser chung.

`TruyenFullSource` là nguồn `READY`. TruyenCV, Truyện Com, TruyenYY, WikiDich và Sáng Tác Việt giữ `DEGRADED` tới khi live-test trên APK thật.

## `downloads` và `following`

`ChapterDownloadWorker` tải tuần tự, tối đa 40 chương mỗi worker, hỗ trợ khoảng chương theo vị trí mục lục, retry, bỏ qua chương đã lưu và hủy từ notification.

Theo dõi chương mới là opt-in. Worker định kỳ lấy các truyện lâu chưa kiểm tra nhất, lưu `latestKnownChapterIndex` cùng `newChapterCount`, và deep link notification truy vấn lại Room để hoạt động cả cold start.

## `playback`

`ReaderPlaybackService` quản lý MediaSession, notification, audio focus, wake lock, sleep timer, prefetch và chuyển chương tự động.

Cấu hình giọng có ba lớp: toàn cục, theo truyện và theo vai. Khi một chương có kế hoạch phân vai AI hợp lệ, assignment theo `paragraphIndex` được ưu tiên; nếu không, `VoiceRoleResolver` thủ công xử lý prefix/alias. Mọi kế hoạch được ràng buộc với SHA-256 của nội dung gốc để dữ liệu cũ không áp dụng nhầm khi chương thay đổi.

Nhạc cảnh dùng `scene_music_cues`: mỗi cue chọn một track local theo mốc đoạn. Chỉ ID, tên và tag của track được gửi cho AI; tệp âm thanh không được tải lên. MediaPlayer chuyển bài tại cue, lặp bài và áp dụng duck factor khi TTS nói. URI SAF là dữ liệu cục bộ, không được backup sang thiết bị khác.

## `vietphrase`

VietPhrase chạy hoàn toàn cục bộ:

- Quy tắc ưu tiên cao hơn chạy trước, sau đó ưu tiên cụm dài hơn.
- Replacement không được đưa lại vào bộ so khớp, tránh thay thế dây chuyền.
- Có bật/tắt, chỉnh sửa và xóa quy tắc.
- Nhập UTF-8 dạng TSV hoặc `nguồn=đích`, tối đa 16 MiB và 100.000 quy tắc.
- Xuất canonical TSV có escape tab, xuống dòng và dấu gạch chéo ngược.

Kết quả VietPhrase được tạo tại chỗ và không gọi mạng.

## `ai`

`OnlineAiServices` hỗ trợ endpoint chat-completions tương thích OpenAI cho đúng ba tác vụ:

1. Dịch chương, giữ marker `[[P:n]]` để bảo toàn ranh giới đoạn.
2. Phân vai AI, trả line protocol ánh xạ đoạn → tên vai.
3. Lập cue nhạc cảnh, trả line protocol đoạn → track ID.

Hàng rào bảo mật:

- Consent và trạng thái bật AI là bắt buộc.
- API key mã hóa AES-GCM bằng Android Keystore và không vào backup.
- Endpoint phải là HTTPS công khai; DNS được kiểm tra lại để chặn localhost, private, link-local và multicast.
- Redirect bị tắt.
- Phản hồi được đọc streaming với giới hạn ký tự.
- Protocol parser loại dữ liệu trùng, sai marker, sai chỉ số hoặc giá trị ngoài phạm vi.
- Cache bản dịch và kế hoạch AI gắn fingerprint/hash với nội dung gốc và cấu hình provider.
- Consent không tự khôi phục từ backup ZIP hoặc Android Auto Backup.

Không có AI offline, roleplay đầy đủ hoặc tự động gọi AI trong nền.

## `audio`

`AudioExportWorker` chỉ lấy chương đã có trong Room. Assignment AI hợp lệ được ưu tiên cho từng đoạn, sau đó mới tới vai thủ công và profile truyện.

Pipeline tổng hợp WAV segment bằng Android TTS, chuẩn hóa về PCM16, rồi ghép WAV hoặc mã hóa AAC-LC vào M4A qua `MediaCodec`/`MediaMuxer`. Job có progress, retry, cancel và cleanup. Nhạc cảnh hiện chỉ chạy khi nghe trực tiếp, chưa được trộn vào file xuất.

## `transfer` và bảo mật sao lưu

Backup ZIP do người dùng chủ động chọn dùng format 6, có SHA-256, giới hạn entry/kích thước và restore trong Room transaction. Nó lưu reader settings, following counters, pronunciation dictionary, VietPhrase và profile/vai giọng. Cấu hình AI được lưu ở trạng thái tắt; consent và API key không được khôi phục.

Android Auto Backup/device transfer loại trừ toàn bộ database, DataStore, API key mã hóa và cookie phiên. Điều này tránh dữ liệu nhạy cảm hoặc consent tự di chuyển sang thiết bị khác. Backup ZIP có kiểm soát là cơ chế portable chính.

## `data`

Room schema 7 có migration explicit `1 → 2 → 3 → 4 → 5 → 6 → 7`.

Các bảng mới của P4:

- `viet_phrase_rules`
- `chapter_transforms`
- `chapter_voice_assignments`
- `scene_music_tracks`
- `scene_music_cues`

`chapter_transforms` ghi loại transform, hash/fingerprint nguồn và metadata để playback/export chỉ dùng kết quả còn tương thích với chương hiện tại.

## Hướng production

- Chạy Gradle Sync, Room KAPT, Compose compiler, unit test, Lint và `assembleDebug` trên SDK 36.
- Test API endpoint thật với consent, lỗi quota, timeout, phản hồi lớn và khóa sai.
- Test TTS/MediaCodec/MediaMuxer/MediaPlayer trên Google, Samsung và OEM khác.
- Test foreground service, process death, audio interruption và WorkManager quota.
- Live-test mọi adapter `DEGRADED`, WebView login, cookie expiry và Android Keystore.
- Thêm benchmark, baseline profile, screenshot và accessibility test trước phát hành công khai.
