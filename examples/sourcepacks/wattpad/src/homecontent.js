load("config.js");

function execute(input, page) {
  var parts = String(input || "").split("|");
  var filter = parts[0] || "hot";
  var completed = parts[1] || "0";
  var fallbackQuery = parts.slice(2).join("|") || "truyện tiếng Việt";
  return feedStories(filter, completed, fallbackQuery, page || "0", DEFAULT_LIMIT);
}
