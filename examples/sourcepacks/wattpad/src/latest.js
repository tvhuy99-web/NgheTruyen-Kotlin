load("config.js");

var LATEST_FIELDS = { fields: "id,parts(id,title,url,deleted,draft)" };

function execute(url) {
  var storyId = extractStoryId(url);
  if (!storyId) return Response.error("URL truyện Wattpad không hợp lệ");
  var data = fetchFirstJson([API_V3 + "/stories/" + storyId, API_V4 + "/stories/" + storyId], LATEST_FIELDS);
  if (!data || !data.parts || !data.parts.length) return Response.success(null);
  for (var i = data.parts.length - 1; i >= 0; i--) {
    var part = data.parts[i] || {};
    if (part.deleted === true || part.draft === true) continue;
    var id = part.id != null ? String(part.id) : extractChapId(part.url);
    var chapterUrl = cleanText(part.url || "");
    if (!chapterUrl && id) chapterUrl = BASE_URL + "/" + id;
    if (chapterUrl && !/^https?:\/\//i.test(chapterUrl)) chapterUrl = BASE_URL + (chapterUrl.charAt(0) === "/" ? chapterUrl : "/" + chapterUrl);
    return Response.success({ name: cleanText(part.title || ("Chương " + (i + 1))), url: chapterUrl, index: i });
  }
  return Response.success(null);
}
