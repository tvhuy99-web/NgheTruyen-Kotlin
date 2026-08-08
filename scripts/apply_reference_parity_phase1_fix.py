from pathlib import Path
root = Path(__file__).resolve().parents[1]

# Fix Library named argument introduced by the main patch.
p = root / "app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt"
s = p.read_text()
old = 'FollowingList(followingVisible, onFollowingClick, onCheckFollowing = {})'
new = 'FollowingList(followingVisible, onFollowingClick, onCheckNow = {})'
if old in s:
    s = s.replace(old, new, 1)
p.write_text(s)

# Box is a foundation layout primitive, not a Material3 component.
p = root / "app/src/main/java/vn/nghetruyen/app/ui/screens/ExploreScreen.kt"
s = p.read_text()
s = s.replace('import androidx.compose.material3.Box\n', '')
if 'import androidx.compose.foundation.layout.Box\n' not in s:
    marker = 'import androidx.compose.foundation.layout.Arrangement\n'
    if marker not in s:
        raise SystemExit('Explore Box import anchor not found')
    s = s.replace(marker, marker + 'import androidx.compose.foundation.layout.Box\n', 1)
p.write_text(s)

print('REFERENCE_PARITY_PHASE1_FIX_OK')
