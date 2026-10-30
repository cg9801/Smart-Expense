/**
 * 前端原型本地预览服务器（不参与生产，仅用于浏览器查看 templates/ 下的静态原型页）
 *
 * 用法：node scripts/preview.mjs   然后浏览器访问 http://localhost:8787/report-list.html
 *
 * 为什么需要它：原型页用 /css/common.css、/report-list.html 这类根路径互相引用，
 * 双击 HTML（file://）解析不了根路径；而这个路径结构和你以后用 Spring Boot
 * 启动时完全一致（/css/* 来自 static/，页面来自 templates/），切换成本为零。
 */
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { exec } from "node:child_process";

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "Smart-Expense", "src", "main", "resources");

const pages = {
  "/report-list.html": "templates/report-list.html",
  "/approval-todo.html": "templates/approval-todo.html",
  "/admin-console.html": "templates/admin-console.html",
  "/report-submit.html": "templates/report-submit.html",
};

http.createServer((req, res) => {
  const url = req.url.split("?")[0];
  let rel = pages[url];
  if (!rel && url.startsWith("/css/")) rel = "static" + url;
  if (!rel) { res.writeHead(404); return res.end("not found"); }
  fs.readFile(path.join(root, rel), (err, data) => {
    if (err) { res.writeHead(404); return res.end("not found"); }
    res.writeHead(200, { "Content-Type": rel.endsWith(".css") ? "text/css" : "text/html; charset=utf-8" });
    res.end(data);
  });
}).listen(8787, () => {
  const url = "http://localhost:8787/report-list.html";
  console.log("预览已启动: " + url);
  const open = process.platform === "win32" ? "start" : process.platform === "darwin" ? "open" : "xdg-open";
  exec(`${open} ${url}`);
});
