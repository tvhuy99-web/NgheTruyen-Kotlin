#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path('app/src/main/java')
patterns = {
    'Button': re.compile(r'\bButton\s*\('),
    'TextButton': re.compile(r'\bTextButton\s*\('),
    'IconButton': re.compile(r'\bIconButton\s*\('),
    'ReferenceActionButton': re.compile(r'\bReferenceActionButton\s*\('),
    'ReaderButton': re.compile(r'\bReaderButton\s*\('),
    'ReaderMenuButton': re.compile(r'\bReaderMenuButton\s*\('),
    'clickable': re.compile(r'\.clickable\s*\('),
    'toggleable': re.compile(r'\.toggleable\s*\('),
    'Switch': re.compile(r'\bSwitch\s*\('),
    'Checkbox': re.compile(r'\bCheckbox\s*\('),
    'RadioButton': re.compile(r'\bRadioButton\s*\('),
    'Slider': re.compile(r'\bSlider\s*\('),
    'DropdownMenuItem': re.compile(r'\bDropdownMenuItem\s*\('),
}

files = sorted(ROOT.rglob('*.kt'))
print(f'A11Y_INVENTORY_FILES={len(files)}')
count = 0
for path in files:
    lines = path.read_text(encoding='utf-8').splitlines()
    for i, line in enumerate(lines):
        kinds = [name for name, rx in patterns.items() if rx.search(line)]
        if not kinds:
            continue
        count += 1
        lo = max(0, i - 2)
        hi = min(len(lines), i + 5)
        context = '\n'.join(f'{n+1:5d}: {lines[n]}' for n in range(lo, hi))
        window = '\n'.join(lines[max(0, i-4):min(len(lines), i+10)])
        explicit = any(token in window for token in [
            'contentDescription', 'accessibilityLabel', 'stateDescription',
            'clearAndSetSemantics', 'semantics {', 'role = Role.',
        ])
        visible_text = bool(re.search(r'Text\s*\(\s*"[^\"]+"', window))
        print('\n=== INTERACTIVE ===')
        print(f'FILE={path}')
        print(f'LINE={i+1}')
        print(f'KIND={",".join(kinds)}')
        print(f'EXPLICIT_A11Y={int(explicit)}')
        print(f'VISIBLE_TEXT_NEARBY={int(visible_text)}')
        print(context)
print(f'\nA11Y_INTERACTIVE_TOTAL={count}')
