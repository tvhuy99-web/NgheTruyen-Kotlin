'use strict';

function execute(input, data) {
    return Response.success([
        { input: input, data: data }
    ], data + '/next');
}
