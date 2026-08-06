load("config.js");

function execute(input, page) {
  input = String(input || "");
  if (input.indexOf("author:") === 0) {
    var username = cleanText(input.substring(7));
    if (username) {
      var offset = String(page || "0");
      var data = fetchFirstJson([
        API_V4 + "/users/" + encodeURIComponent(username) + "/stories",
        API_V4 + "/users/" + encodeURIComponent(username) + "/stories/"
      ], {
        fields: "stories(id,title,url,cover,user(name,username)),nextUrl",
        offset: offset,
        limit: String(DEFAULT_LIMIT)
      });
      if (data && data.stories) return parseStories(data, offset, DEFAULT_LIMIT);
    }
    return searchStories(username, page || "0", DEFAULT_LIMIT);
  }
  return searchStories(input, page || "0", DEFAULT_LIMIT);
}
