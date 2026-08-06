load("config.js");

function execute(input, page) {
  return searchStories(String(input || "").replace(/-/g, " "), page || "0", DEFAULT_LIMIT);
}
