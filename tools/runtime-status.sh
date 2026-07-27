#!/usr/bin/env bash

set -u

printf '%s\n' '六维达康本地入口：'
printf '%s\n' '  8081  PC 一期本地验收/唯一入口'
printf '%s\n' '  13321 Vite 开发预览（仅 HMR）'
printf '%s\n' '  13330 Spring Boot API（不是页面）'

for port in 8081 13321 13330; do
  printf '\n[%s] 监听进程\n' "$port"
  lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null || printf '%s\n' '未监听'
done

for url in 'http://localhost:8081/' 'http://localhost:13321/' 'http://localhost:13330/dakangApi/'; do
  printf '\n[%s] 响应\n' "$url"
  curl -sS -I --max-time 4 "$url" 2>/dev/null \
    | awk 'BEGIN { IGNORECASE=1 } /^HTTP\// || /^Server:/ || /^X-Dakang-Entry:/ || /^X-Dakang-Canonical-Entry:/' \
    || printf '%s\n' '无法访问'
done
