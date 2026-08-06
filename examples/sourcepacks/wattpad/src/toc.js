load("config.js");

var TOC_FIELDS = {
  fields: "id,parts(id,title,url,deleted,draft)"
};

function execute(url) {
  var storyId = extractStoryId(url);
  if (!storyId) return Response.error("URL truyện Wattpad không hợp lệ");

  var data = fetchFirstJson([
    API_V3 + "/stories/" + storyId,
    API_V4 + "/stories/" + storyId
  ], TOC_FIELDS);

  if (!data || !data.parts || !data.parts.length) return Response.success([]);

  var list = [];
  var seen = {};
  for (var i = 0; i < data.parts.length; i++) {
    var v = data.parts[i] || {};
    if (v.deleted === true || v.draft === true) continue;
    var id = v.id != null ? String(v.id) : extractChapId(v.url);
    if (!id || seen[id]) continue;
    seen[id] = true;
    var chapterUrl = cleanText(v.url || "");
    if (!chapterUrl) chapterUrl = BASE_URL + "/" + id;
    if (!/^https?:\/\//i.test(chapterUrl)) chapterUrl = BASE_URL + (chapterUrl.charAt(0) === "/" ? chapterUrl : "/" + chapterUrl);
    list.push({
      name: cleanText(v.title || ("Chương " + (i + 1))),
      url: chapterUrl
    });
  }

  return Response.success(list);
}
