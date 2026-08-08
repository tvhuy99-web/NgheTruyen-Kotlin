from pathlib import Path
p = Path(__file__).resolve().parents[1] / "app/src/main/java/vn/nghetruyen/app/ui/screens/LibraryScreen.kt"
s = p.read_text()
old = 'FollowingList(followingVisible, onFollowingClick, onCheckFollowing = {})'
new = 'FollowingList(followingVisible, onFollowingClick, onCheckNow = {})'
if old not in s:
    raise SystemExit('phase1 fix target not found')
p.write_text(s.replace(old, new, 1))
print('REFERENCE_PARITY_PHASE1_FIX_OK')
