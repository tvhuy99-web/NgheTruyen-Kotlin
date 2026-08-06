load("config.js");

var DETAIL_FIELDS = {
  fields: "id,title,cover,url,description,user(name,username),completed,categories,tags,readCount,voteCount,numParts,modifyDate"
};

function execute(url) {
  var storyId = extractStoryId(url);
  if (!storyId) return Response.error("URL truyện Wattpad không hợp lệ");

  var data = fetchFirstJson([
    API_V3 + "/stories/" + storyId,
    API_V4 + "/stories/" + storyId
  ], DETAIL_FIELDS);

  if (!data || !data.title) return Response.error("Không thể tải thông tin truyện Wattpad");

  var genreStr = "";
  var genres = [];
  var firstTag = null;

  if (data.tags && data.tags.length) {
    for (var ti = 0; ti < data.tags.length; ti++) {
      var tag = cleanText(data.tags[ti]);
      if (!tag) continue;
      if (!firstTag) firstTag = tag;
      if (genreStr) genreStr += ", ";
      genreStr += tag;
      if (genres.length < 20) genres.push({ title: tag, input: tag, script: "genrecontent.js" });
    }
  }

  if (!genreStr && data.categories && data.categories.length) {
    for (var ci = 0; ci < data.categories.length; ci++) {
      var c = data.categories[ci];
      var cname = "";
      if (typeof c === "string") cname = c;
      else if (c && typeof c === "object") cname = c.viName || c.name || c.enName || "";
      cname = cleanText(cname);
      if (!cname) continue;
      if (genreStr) genreStr += ", ";
      genreStr += cname;
      genres.push({ title: cname, input: cname, script: "genrecontent.js" });
    }
  }

  var suggests = [];
  if (firstTag) suggests.push({ title: "Truyện cùng thể loại", input: firstTag, script: "suggest.js" });

  var authorName = data.user ? cleanText(data.user.username || data.user.name || "") : "";
  if (authorName) suggests.push({ title: "Truyện cùng tác giả", input: "author:" + authorName, script: "suggest.js" });

  return Response.success({
    name: cleanText(data.title),
    cover: cleanText(data.cover || ""),
    host: BASE_URL,
    author: data.user ? cleanText(data.user.name || data.user.username || "") : "",
    description: String(data.description || ""),
    detail: genreStr,
    ongoing: data.completed === true ? false : true,
    genres: genres,
    suggests: suggests
  });
}
