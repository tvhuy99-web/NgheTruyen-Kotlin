'use strict';

function execute(url) {
    return Response.success({
        name: 'Fixture Story',
        author: 'Fixture Author',
        cover: '/cover.jpg',
        description: 'Fixture description',
        host: 'https://example.invalid',
        url: url,
        genres: [
            { title: 'Fixture Genre', input: '/genre', script: 'list.js' }
        ],
        suggests: {
            title: 'Related',
            input: url,
            script: 'list.js'
        }
    });
}
