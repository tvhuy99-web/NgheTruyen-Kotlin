'use strict';
load('crypto.js');

function execute(query, page) {
    var doc = Html.parse("<div id='content'><script>bad()</script><p data-k='a'>One</p><p data-k='b'>Two</p></div>");
    doc.select('script').forEach(function (el) { el.remove(); });
    var paragraphs = doc.select('p');
    var names = paragraphs.map(function (el) { return el.text(); }).join('|');
    var firstAttr = paragraphs.first().attributes()['data-k'];
    var hash = CryptoJS.MD5(query).toString();
    return Response.success([
        {
            query: query,
            domain: DOMAIN,
            mapped: names,
            firstAttr: firstAttr,
            cleaned: doc.select('#content').html(),
            md5: hash
        }
    ], page + '/next?opaque=1');
}
