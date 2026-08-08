from pathlib import Path
import subprocess

# Reuse the already-proven phase-3 verification job as a runner for phase 4.
phase4 = Path('scripts/apply_reference_parity_phase4.py').resolve()
exec(phase4.read_text(), {'__name__': '__main__', '__file__': str(phase4)})

product_files = [
    'app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformModels.kt',
    'app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt',
    'app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt',
    'app/src/main/java/vn/nghetruyen/app/ui/screens/ReferencePersonalScreen.kt',
    'app/src/main/java/vn/nghetruyen/app/ui/ReferenceNgheTruyenApp.kt',
    'app/src/main/java/vn/nghetruyen/app/MainActivity.kt',
]
subprocess.run(['git', 'config', 'user.name', 'reference-parity-bot'], check=True)
subprocess.run(['git', 'config', 'user.email', 'reference-parity-bot@users.noreply.github.com'], check=True)
subprocess.run(['git', 'add', '--', *product_files], check=True)
if subprocess.run(['git', 'diff', '--cached', '--quiet']).returncode != 0:
    subprocess.run(['git', 'commit', '-m', 'fix: match reference installed extension actions'], check=True)
    subprocess.run(['git', 'push', 'origin', 'HEAD:agent/reference-ui-position-parity'], check=True)

ui = Path('app/src/main/java/vn/nghetruyen/app/ui/screens/ReferencePersonalScreen.kt').read_text()
manager = Path('app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformManager.kt').read_text()
vm = Path('app/src/main/java/vn/nghetruyen/app/ui/AppViewModel.kt').read_text()
main = Path('app/src/main/java/vn/nghetruyen/app/MainActivity.kt').read_text()
model = Path('app/src/main/java/vn/nghetruyen/app/sourceplatform/SourcePlatformModels.kt').read_text()
block = ui[ui.index('private fun InstalledExtensionsReferenceDialog'):ui.index('private fun RepositoriesReferenceDialog')]
for token in ['TẮT','BẬT','TƯƠNG THÍCH','KIỂM TRA NATIVE','KIỂM TRA NGUỒN','CẬP NHẬT','XUẤT','XÓA']:
    assert token in block, token
positions = [block.index(token) for token in ['TẮT','TƯƠNG THÍCH','KIỂM TRA NATIVE','KIỂM TRA NGUỒN','CẬP NHẬT','XUẤT','XÓA']]
assert positions == sorted(positions), positions
assert 'XÓA TIỆN ÍCH' in block
assert 'Dữ liệu truyện đã tải sẽ được giữ lại.' in block
assert 'if (pack.removable)' in block
assert 'SettingsButton("CẬP NHẬT")' in block
assert 'SettingsButton("XUẤT")' in block
assert 'removable: Boolean' in model
assert 'builtinSourceIds' in manager
assert 'removeInstalledPack' in manager
assert 'exportInstalledPack' in manager
assert 'ZipOutputStream' in manager
assert 'updateSourcePack' in vm and 'Không có bản cập nhật.' in vm
assert 'exportSourcePack' in vm and 'removeSourcePack' in vm
assert 'sourcePackExportLauncher' in main
assert 'CreateDocument("application/zip")' in main
print('REFERENCE_EXTENSION_PARITY_ASSERTIONS_OK')
