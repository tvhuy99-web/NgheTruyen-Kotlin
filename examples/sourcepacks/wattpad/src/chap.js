load("config.js");

var EMPTY_P_RE = /<p[^>]*>\s*(<br\s*\/?>)?\s*<\/p>/gi;
var MULTI_BR_RE = /(<br\s*\/?>\s*){3,}/gi;

function execute(url) {
  var chapId = extractChapId(url);
  if (!chapId) return Response.error("URL chương Wattpad không hợp lệ");

  var apiUrl = APIV2 + "/storytext?id=" + encodeURIComponent(chapId);
  var html = fetchWattpadHtml(apiUrl);
  if (!html || html.length < 10) return Response.error("Không thể tải nội dung chương Wattpad");

  html = String(html)
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<style[\s\S]*?<\/style>/gi, "")
    .replace(EMPTY_P_RE, "")
    .replace(MULTI_BR_RE, "<br><br>");

  return Response.success(html);
}
