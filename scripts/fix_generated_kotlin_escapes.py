from pathlib import Path

path = Path("source-vbook/src/main/kotlin/vn/nghetruyen/source/vbook/VBookStoryNormalizer.kt")
text = path.read_text(encoding="utf-8")
replacements = {
    r'Regex("\s+")': r'Regex("""\s+""")',
    r'Regex("[\r\n]+")': r'Regex("""[\r\n]+""")',
    r'Regex("(?i)^chương\s+\d+\s*[:：].*")': r'Regex("""(?i)^chương\s+\d+\s*[:：].*""")',
}
for old, new in replacements.items():
    text = text.replace(old, new)
path.write_text(text, encoding="utf-8")
print("Repaired generated Kotlin regex literals")
