from pathlib import Path

path = Path('app/src/main/java/vn/nghetruyen/app/ai/XpkUnifiedNarrationPrompt.kt')
text = path.read_text(encoding='utf-8')
expected = '                appendLine("- Nếu các UNIT sau không nhắc lại nguồn âm nhưng scene vật lý vẫn liên tục và không có bằng chứng nguồn đã dừng, tiếp tục ambience qua các UNIT đó; chỉ đổi/dừng ở biến cố môi trường thật sự.")'
current = '                appendLine("- Nếu cảnh vẫn liên tục và không có bằng chứng nguồn âm dừng, tiếp tục giữ ambience dù các UNIT sau không nhắc lại nó. Chỉ đổi/dừng tại UNIT đầu nơi môi trường thật sự thay đổi.")'
marker = 'Ở ranh giới chương cũng áp dụng đúng quy tắc này'
if marker in text or expected in text:
    print('Ambience prompt continuity anchor already prepared.')
elif current in text:
    path.write_text(text.replace(current, expected, 1), encoding='utf-8')
    print('Normalized ambience continuity anchor for verified patch.')
else:
    raise SystemExit('Không tìm thấy quy tắc ambience continuity hiện tại để chuẩn bị patch.')
