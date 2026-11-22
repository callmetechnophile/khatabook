FROM node:22-slim
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY server ./server
COPY src ./src
COPY public ./public
ENV NODE_ENV=production
# The host provides PORT; the server reads it. Ollama/Qwen is not available in this image: the fallback quietly returns nothing.
CMD ["node", "server/index.js"]
