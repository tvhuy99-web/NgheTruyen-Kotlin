from pathlib import Path

# Reuse the already-proven phase-3 verification job as a runner for phase 4.
exec(Path('scripts/apply_reference_parity_phase4.py').read_text(), {'__name__': '__main__'})

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
