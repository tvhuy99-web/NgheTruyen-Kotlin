load("config.js");

function execute() {
  return Response.success([
    { title: "Nổi bật", script: "homecontent.js", input: "hot|0|truyện tiếng Việt" },
    { title: "Mới cập nhật", script: "homecontent.js", input: "new|0|truyện mới cập nhật tiếng Việt" },
    { title: "Mới nhất", script: "homecontent.js", input: "fresh|0|truyện mới tiếng Việt" },
    { title: "Hoàn thành", script: "homecontent.js", input: "hot|1|truyện hoàn thành tiếng Việt" }
  ]);
}
