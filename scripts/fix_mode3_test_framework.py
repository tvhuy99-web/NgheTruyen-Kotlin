from pathlib import Path

path = Path("source-vbook/src/test/kotlin/vn/nghetruyen/source/vbook/VBookBrowserChallengeDetectorTest.kt")
text = path.read_text(encoding="utf-8")
text = text.replace(
    "import kotlin.test.Test\nimport kotlin.test.assertFalse\nimport kotlin.test.assertTrue\n",
    "import org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n",
)
path.write_text(text, encoding="utf-8")
print("Adjusted vBook regression test to JUnit4")
