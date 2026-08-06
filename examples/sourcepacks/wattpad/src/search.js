load("config.js");

function execute(key, page) {
  var query = cleanText(key);
  if (!query) return Response.success([], null);
  return searchStories(query, page || "0", DEFAULT_LIMIT);
}
