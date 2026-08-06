var BASE_URL = "https://www.wattpad.com";
var API_V3 = BASE_URL + "/api/v3";
var API_V4 = BASE_URL + "/v4";
var APIV2 = BASE_URL + "/apiv2";
var LANG_VI = "19";
var DEFAULT_LIMIT = 30;

var STORY_ID_RE = /\/story\/(\d+)/i;
var STORY_ID_RE2 = /\/(\d+)(?:[-\/?#]|$)/;
var CHAP_ID_RE = /wattpad\.com\/(\d+)(?:[-\/?#]|$)/i;

function extractStoryId(url) {
  var text = String(url || "");
  var m = STORY_ID_RE.exec(text) || STORY_ID_RE2.exec(text);
  return m ? m[1] : null;
}

function extractChapId(url) {
  var text = String(url || "");
  var m = CHAP_ID_RE.exec(text) || STORY_ID_RE2.exec(text);
  return m ? m[1] : null;
}

var FETCH_OPTIONS = {
  headers: {
    "User-Agent": "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
    "Accept": "*/*",
    "Accept-Language": "vi-VN,vi;q=0.9,en;q=0.6",
    "Referer": BASE_URL + "/"
  }
};

function cleanText(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function buildUrl(base, params) {
  if (!params) return base;
  var qs = [];
  for (var key in params) {
    if (params[key] === null || params[key] === undefined || params[key] === "") continue;
    qs.push(encodeURIComponent(key) + "=" + encodeURIComponent(params[key]));
  }
  if (!qs.length) return base;
  var joiner = base.indexOf("?") >= 0 ? "&" : "?";
  if (/[?&]$/.test(base)) joiner = "";
  return base + joiner + qs.join("&");
}

function fetchWattpadJson(url, params) {
  var fullUrl = buildUrl(url, params);
  var res = fetch(fullUrl, FETCH_OPTIONS);
  if (res && !res.ok && !(res.status >= 400 && res.status < 500)) {
    res = fetch(fullUrl, FETCH_OPTIONS);
  }
  if (!res || !res.ok) return null;
  try { return res.json(); } catch (e) { return null; }
}

function fetchFirstJson(urls, params) {
  for (var i = 0; i < urls.length; i++) {
    var data = fetchWattpadJson(urls[i], params);
    if (data) return data;
  }
  return null;
}

function fetchWattpadHtml(url) {
  var res = fetch(url, FETCH_OPTIONS);
  if (res && !res.ok && !(res.status >= 400 && res.status < 500)) {
    res = fetch(url, FETCH_OPTIONS);
  }
  if (!res || !res.ok) return null;
  try { return res.text(); } catch (e) { return null; }
}

function nextOffsetFromData(data, currentOffset, count, limit) {
  var nextUrl = data && data.nextUrl ? String(data.nextUrl) : "";
  var m = /[?&]offset=(\d+)/i.exec(nextUrl) || /offset=(\d+)/i.exec(nextUrl);
  if (m) return m[1];

  var offset = parseInt(currentOffset || "0", 10);
  if (isNaN(offset) || offset < 0) offset = 0;
  var size = Number(count || 0);
  var pageSize = Number(limit || DEFAULT_LIMIT);
  if (size >= pageSize) return String(offset + pageSize);
  return null;
}

function parseStories(data, currentOffset, limit) {
  if (!data || !data.stories || !data.stories.length) return Response.success([], null);

  var list = [];
  var seen = {};
  var stories = data.stories;

  for (var i = 0; i < stories.length; i++) {
    var v = stories[i] || {};
    var storyId = v.id != null ? String(v.id) : extractStoryId(v.url);
    var linkPath = cleanText(v.url || "");
    if (!linkPath && storyId) linkPath = "/story/" + storyId;
    if (!linkPath) continue;
    if (/^https?:\/\//i.test(linkPath)) linkPath = linkPath.replace(/^https?:\/\/(?:www\.)?wattpad\.com/i, "");
    if (linkPath.charAt(0) !== "/") linkPath = "/" + linkPath;

    var key = storyId || linkPath;
    if (seen[key]) continue;
    seen[key] = true;

    list.push({
      name: cleanText(v.title || "(Không có tên)"),
      link: linkPath,
      host: BASE_URL,
      cover: cleanText(v.cover || ""),
      description: v.user ? cleanText(v.user.name || v.user.username || "") : ""
    });
  }

  return Response.success(list, nextOffsetFromData(data, currentOffset, stories.length, limit));
}

function parseSearchHtml(query, offset, limit) {
  var off = parseInt(offset || "0", 10);
  if (isNaN(off) || off < 0) off = 0;
  var pageSize = Number(limit || DEFAULT_LIMIT);
  var page = Math.floor(off / pageSize) + 1;
  var url = BASE_URL + "/search/" + encodeURIComponent(query) + "?page=" + page;

  var res = fetch(url, FETCH_OPTIONS);
  if (!res || !res.ok) return null;

  var doc = null;
  try { doc = res.html(); } catch (e) {}
  if (!doc) return null;

  var list = [];
  var seen = {};
  var selectors = [
    "a.story-card",
    "article a[href*='/story/']",
    "a[href*='/story/']"
  ];

  for (var si = 0; si < selectors.length && list.length === 0; si++) {
    var nodes = doc.select(selectors[si]);
    for (var i = 0; i < nodes.size(); i++) {
      var item = nodes.get(i);
      var href = cleanText(item.absUrl("href") || item.attr("href"));
      var id = extractStoryId(href);
      if (!id || seen[id]) continue;

      var titleNode = item.select(".title").first()
        || item.select("[data-testid=story-title]").first()
        || item.select("h3").first()
        || item.select("h2").first();

      var title = titleNode ? cleanText(titleNode.text()) : cleanText(item.attr("aria-label"));
      if (!title) continue;

      seen[id] = true;
      var image = item.select("img").first();
      var cover = image ? cleanText(image.attr("src") || image.attr("data-src") || image.attr("data-original")) : "";
      var descNode = item.select(".description").first() || item.select("[data-testid=story-description]").first();

      list.push({
        name: title,
        link: "/story/" + id,
        host: BASE_URL,
        cover: cover,
        description: descNode ? cleanText(descNode.text()) : ""
      });
    }
  }

  var next = list.length > 0 ? String(off + pageSize) : null;
  return Response.success(list, next);
}

function searchStories(query, offset, limit) {
  query = cleanText(query);
  offset = String(offset || "0");
  limit = Number(limit || DEFAULT_LIMIT);

  var data = fetchFirstJson([
    API_V4 + "/search/stories/",
    API_V4 + "/search/stories"
  ], {
    query: query,
    language: LANG_VI,
    fields: "stories(id,title,url,cover,user(name,username)),nextUrl",
    offset: offset,
    limit: String(limit)
  });

  if (data && data.stories) return parseStories(data, offset, limit);

  var fallback = parseSearchHtml(query, offset, limit);
  if (fallback) return fallback;
  return Response.error("Không thể tải dữ liệu Wattpad");
}

function feedStories(filter, completed, fallbackQuery, offset, limit) {
  offset = String(offset || "0");
  limit = Number(limit || DEFAULT_LIMIT);
  var params = {
    filter: filter,
    language: LANG_VI,
    fields: "stories(id,title,url,cover,user(name,username)),nextUrl",
    offset: offset,
    limit: String(limit)
  };
  if (completed === "1" || completed === 1 || completed === true) params.completed = "1";

  var data = fetchFirstJson([
    API_V4 + "/stories",
    API_V4 + "/stories/"
  ], params);

  if (data && data.stories) return parseStories(data, offset, limit);
  return searchStories(fallbackQuery || "truyện tiếng Việt", offset, limit);
}
