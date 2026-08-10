function execute(data) {
    return Response.success({
        type: 'native',
        data: 'https://media.example.invalid/video.m3u8?token=' + String(data),
        host: 'https://example.invalid',
        mimeType: 'application/x-mpegURL',
        headers: {Referer: 'https://example.invalid/'},
        timeSkip: []
    });
}
