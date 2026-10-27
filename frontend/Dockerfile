# ============================================================
# 前端镜像
#
# 多阶段：Node 只用于构建，最终镜像里没有任何 JS 工具链 ——
# 运行期就是一个 nginx 托管静态文件并反代 /api。
#
# 构建（在仓库根目录执行）：
#   docker build -f frontend/Dockerfile -t personal-ai-os-frontend ./frontend
# ============================================================

# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /build

# 先只拷清单：依赖不变时 npm ci 这一层直接命中缓存
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# 构建产物里前端访问的是相对路径 /api/v1（见 src/api/http.ts 的 apiBaseUrl 默认值），
# 因此**不需要**在构建期注入后端地址 —— 换域名也不必重新构建镜像
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine

# 默认配置会 `location /` 只找静态文件，SPA 的深链接（/settings）会 404，
# 且没有 /api 反代 —— 必须整体替换
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
