FROM node:20-alpine

WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN corepack enable \
  && corepack prepare pnpm@10.10.0 --activate \
  && pnpm install --frozen-lockfile --prod

COPY sim.js ./

CMD ["node", "sim.js", "DK-DEV-0001", "lock-model"]
