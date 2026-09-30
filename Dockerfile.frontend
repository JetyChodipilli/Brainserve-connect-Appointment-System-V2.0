FROM node:22.14-alpine AS dependencies
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM dependencies AS build
ARG NEXT_PUBLIC_API_BASE_URL
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
COPY . .
RUN npm run build

FROM dependencies AS production-dependencies
RUN npm prune --omit=dev

FROM node:22.14-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=3000
RUN addgroup -S brainserve && adduser -S -G brainserve brainserve \
    && mkdir -p /app/.wrangler && chown brainserve:brainserve /app/.wrangler
COPY --chown=brainserve:brainserve --from=build /app/package.json /app/package-lock.json ./
COPY --chown=brainserve:brainserve --from=production-dependencies /app/node_modules ./node_modules
COPY --chown=brainserve:brainserve --from=build /app/dist ./dist
COPY --chown=brainserve:brainserve --from=build /app/.openai ./.openai
COPY --chown=brainserve:brainserve --from=build /app/vite.config.ts ./vite.config.ts
COPY --chown=brainserve:brainserve --from=build /app/worker ./worker
COPY --chown=brainserve:brainserve --from=build /app/build ./build
COPY --chown=brainserve:brainserve --from=build /app/scripts ./scripts
USER brainserve
EXPOSE 3000
CMD ["npm", "run", "start", "--", "--port", "3000"]
