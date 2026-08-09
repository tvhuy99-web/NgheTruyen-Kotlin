'use strict';
function execute(query, page) {
    return Response.success([{name:query, link:'/comic/'+query, host:'https://example.invalid'}], page ? '' : 'comic-cursor');
}
