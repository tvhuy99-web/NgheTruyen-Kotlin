'use strict';

function execute(url) {
    return Response.success([
        { name: 'Chapter 1', url: url + '/chapter-1', host: 'https://example.invalid' },
        { name: 'Chapter 2', url: url + '/chapter-2', host: 'https://example.invalid' }
    ]);
}
