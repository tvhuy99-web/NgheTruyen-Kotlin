from pathlib import Path

matcher = Path('app/src/main/java/vn/nghetruyen/app/freesound/Mode3LibraryAssetMatcher.kt')
resolver = Path('app/src/main/java/vn/nghetruyen/app/freesound/FreesoundAutoAudioResolver.kt')


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding='utf-8')
    if new in text:
        print('ALREADY', label)
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one old pattern, found {count}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')
    print('PATCHED', label)


# Local library: semantic selection must not use file/title names at all.
replace_once(
    matcher,
    '(entry.englishTitleTokens + entry.englishMetadataTokens).forEach { token ->',
    'entry.englishMetadataTokens.forEach { token ->',
    'local candidate index description-only',
)
replace_once(
    matcher,
    '''        val audibleText = if (sections.structured) {
            listOf(track.title, sections.shadeText, sections.useText).joinToString(" ")
        } else {
            "${track.title} ${track.tagsCsv}"
        }''',
    '''        val audibleText = if (sections.structured) {
            listOf(sections.shadeText, sections.useText).joinToString(" ")
        } else {
            track.tagsCsv
        }''',
    'local audible concepts description-only',
)
replace_once(
    matcher,
    'englishTitleTokens = FreesoundAutoRequirementAggregator.queryTokens(track.title),',
    'englishTitleTokens = emptySet(),',
    'local title tokens disabled',
)

# Online Freesound: preserve all three evidence sources, but rank description > name > tags.
replace_once(
    resolver,
    'return max(titleCoverage, max(tagCoverage * 0.96, descriptionCoverage * 0.78))',
    'return max(descriptionCoverage, max(titleCoverage * 0.85, tagCoverage * 0.75))',
    'remote lexical weights description-name-tags',
)
replace_once(
    resolver,
    '''            val phraseBonus = when {
                titleNorm.contains(queryNorm) -> 0.20
                tagNorm.contains(queryNorm) -> 0.14
                descriptionNorm.contains(queryNorm) -> 0.08
                else -> 0.0
            }''',
    '''            val phraseBonus = when {
                descriptionNorm.contains(queryNorm) -> 0.20
                titleNorm.contains(queryNorm) -> 0.12
                tagNorm.contains(queryNorm) -> 0.08
                else -> 0.0
            }''',
    'remote phrase bonus description-name-tags',
)

# Prevent strong candidates from all saturating at 1.0 and losing field priority.
replace_once(
    resolver,
    'private const val REMOTE_MIN_SCORE = 0.22',
    'private const val REMOTE_MIN_SCORE = 0.16\n        private const val REMOTE_SCORE_NORMALIZER = 1.38',
    'remote score threshold normalized',
)
replace_once(
    resolver,
    '''            return (
                lexicalCoverage * 0.62 + phraseBonus + categoryBonus + durationBonus +
                    ratingBonus + downloadsBonus + apiScoreBonus + rankBonus
                ).coerceIn(0.0, 1.0)''',
    '''            return (
                (lexicalCoverage * 0.62 + phraseBonus + categoryBonus + durationBonus +
                    ratingBonus + downloadsBonus + apiScoreBonus + rankBonus) / REMOTE_SCORE_NORMALIZER
                ).coerceIn(0.0, 1.0)''',
    'remote score normalization prevents saturation',
)

m = matcher.read_text(encoding='utf-8')
r = resolver.read_text(encoding='utf-8')
assert 'entry.englishMetadataTokens.forEach { token ->' in m
assert 'englishTitleTokens = emptySet(),' in m
assert 'return max(descriptionCoverage, max(titleCoverage * 0.85, tagCoverage * 0.75))' in r
assert 'descriptionNorm.contains(queryNorm) -> 0.20' in r
assert 'titleNorm.contains(queryNorm) -> 0.12' in r
assert 'tagNorm.contains(queryNorm) -> 0.08' in r
assert 'private const val REMOTE_MIN_SCORE = 0.16' in r
assert 'private const val REMOTE_SCORE_NORMALIZER = 1.38' in r
assert '/ REMOTE_SCORE_NORMALIZER' in r
print('Description-first audio matching policy applied successfully.')
