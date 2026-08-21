from pathlib import Path

# The source-vbook module uses JUnit4 directly rather than kotlin.test.
path = Path("source-vbook/src/test/kotlin/vn/nghetruyen/source/vbook/VBookBrowserChallengeDetectorTest.kt")
text = path.read_text(encoding="utf-8")
text = text.replace(
    "import kotlin.test.Test\nimport kotlin.test.assertFalse\nimport kotlin.test.assertTrue\n",
    "import org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n",
)
path.write_text(text, encoding="utf-8")

# The main patch is embedded in Python, so turn the generated Kotlin regex into a raw Kotlin string.
importer = Path("app/src/main/java/vn/nghetruyen/app/freesound/FreesoundImporter.kt")
source = importer.read_text(encoding="utf-8")
old = r'Regex("HTTP\s+(?:429|5\d\d)", RegexOption.IGNORE_CASE)'
new = r'Regex("""HTTP\s+(?:429|5\d\d)""", RegexOption.IGNORE_CASE)'
if source.count(old) != 1:
    raise RuntimeError(f"Expected one retry-regex occurrence, found {source.count(old)}")
importer.write_text(source.replace(old, new, 1), encoding="utf-8")
print("Adjusted vBook test framework and Kotlin retry regex")
