# Bản đồ chuyển đổi XPK → Kotlin

| Nhóm trong gói cũ | Thiết kế Kotlin mới | Trạng thái 0.7 |
|---|---|---|
| `ui/main_layout`, `ui/controller`, `ui/list_router` | Compose screens + `MainUiState` + `AppViewModel` | Có luồng chính, paging và action list |
| `core/database`, `library/service`, `reader/history` | Room entities/DAO + `LibraryRepository` | Đọc tiếp, đánh dấu, theo dõi, offline đã nối |
| `state`, `secure_prefs` | DataStore Preferences | Có source/rate/pitch/auto-next/voice/following/cache quota và reader display |
| `nguon_truyenfull_native.lua` | `TruyenFullSource` + fixtures + HTTPS allowlist | `READY` |
| `nguon_truyencv_native.lua` | `TruyenCvSource` + fixtures | `DEGRADED`, chờ live device verification |
| `nguon_truyencom_native.lua` | `TruyenComSource` + parser fixtures | Port Kotlin, `DEGRADED` |
| Các `sources/*`, `nguon_*` khác | Một `StorySource` Kotlin riêng cho mỗi website | Chờ port |
| `search/global`, `search/smart` | Search theo registry + URL trực tiếp + paging | Search đa nguồn có hủy/merge; fuzzy ranking nâng cao chờ |
| `reader/controller`, `playback`, `tts` | Typed queue + Android TTS foreground service + voice catalog/profile | Đã có auto-next, timer, voice selection và profile theo truyện |
| `media_controls` | Android MediaSession + notification actions | Đã có |
| `next_chapter_prefetch`, `chapter_automation` | Room cache + prefetch 75% + auto transition | Đã có lõi |
| `sonic_tts` và bridge native | Android TTS rate/pitch; DSP mới chỉ khi benchmark cần | Không dùng native cũ |
| `downloads/manager`, webview worker | Planner + batched WorkManager `dataSync` + Room + storage manager | Đã có tải toàn truyện, tải khoảng, xóa và thống kê |
| `import/book_worker`, file importers | `BookImporter` TXT/EPUB/DOCX | Có safety bounds; MOBI/AZW chờ |
| `following/service` | Room + oldest-first WorkManager + deep-link notification | Đã có lõi, chờ device test |
| `transfer/backup`, `restore` | ZIP có version/checksum + merge restore | Format 4, đọc tương thích format 1..4 |
| `ai/service`, offline AI | `TranslationService`, provider online/offline riêng | Contract có; provider chưa cấu hình |
| `voice_cast*` | `VoiceCastPlanner` + profile/segment model | Profile theo truyện có; phân vai theo nhân vật còn contract |
| `scene_music` | `SceneMusicPlanner` + player policy | Contract có |
| `export/audio_*` | TTS-to-file + RIFF/WAVE assembler + foreground worker | WAV cached-content đã có; M4A/MP3 chờ |
| `roleplay_*` | Feature module riêng, state machine + persistence | Chưa triển khai |
| DEX động / Lua runtime / native bridge cũ | Không chuyển sang | Loại bỏ hoàn toàn |

Chi tiết theo từng chức năng: [PARITY_AUDIT_0.7.md](PARITY_AUDIT_0.7.md).

