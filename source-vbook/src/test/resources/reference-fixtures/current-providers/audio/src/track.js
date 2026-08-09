function execute(data) {
    return Response.success({
        type: 'native',
        data: 'https://media.example.invalid/audio.mp3?source=' + String(data),
        host: 'https://example.invalid',
        mimeType: 'audio/mpeg',
        headers: {Referer: 'https://example.invalid/'},
        timeSkip: []
    });
}
