package vn.nghetruyen.app.core.model

const val GLOBAL_VOICE_PROFILE_STORY_ID = "__global_voice_profiles__"

data class GlobalVoiceProfileSeed(
    val name: String,
    val description: String,
    val narrator: Boolean = false,
)

val DEFAULT_GLOBAL_VOICE_PROFILES: List<GlobalVoiceProfileSeed> = listOf(
    GlobalVoiceProfileSeed(
        name = "Người kể chuyện",
        description = "Dùng cho lời dẫn, miêu tả, chuyển cảnh, nội tâm, lời tự nhủ không phát thành tiếng, thông báo hệ thống và phần không xác định chắc chắn người nói.",
        narrator = true,
    ),
    GlobalVoiceProfileSeed("Nam thiếu niên", "Dùng cho lời thoại của nhân vật nam trẻ tuổi, thiếu niên, học sinh hoặc người có cách nói trẻ trung."),
    GlobalVoiceProfileSeed("Nữ thiếu niên", "Dùng cho lời thoại của nhân vật nữ trẻ tuổi, thiếu niên, học sinh hoặc người có cách nói trẻ trung."),
    GlobalVoiceProfileSeed("Nam trung niên", "Dùng cho lời thoại của nhân vật nam trưởng thành, trung niên hoặc người có cách nói chín chắn."),
    GlobalVoiceProfileSeed("Nữ trung niên", "Dùng cho lời thoại của nhân vật nữ trưởng thành, trung niên hoặc người có cách nói chín chắn."),
    GlobalVoiceProfileSeed("Nam cao tuổi", "Dùng cho lời thoại của nhân vật nam lớn tuổi, trưởng bối, ông lão hoặc người có cách nói già dặn."),
    GlobalVoiceProfileSeed("Nữ cao tuổi", "Dùng cho lời thoại của nhân vật nữ lớn tuổi, trưởng bối, bà lão hoặc người có cách nói già dặn."),
)
