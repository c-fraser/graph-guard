FROM node:24-slim

WORKDIR /app
COPY package.json .
RUN npm install --omit=dev
COPY client.mjs .

CMD ["./node_modules/.bin/zx", "client.mjs"]
