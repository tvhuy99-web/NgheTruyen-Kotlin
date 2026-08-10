'use strict';

function execute() {
    return Response.success([
        {
            id: 'latest',
            title: 'Latest',
            type: 'list',
            items: [],
            action: {
                type: 'list',
                script: 'list.js',
                input: '/latest',
                data: 'server-a'
            }
        }
    ]);
}
