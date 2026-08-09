'use strict';
function execute(text, from, to, source) {
    return Response.success({
        text: text,
        from: from,
        to: to,
        source: source
    });
}
