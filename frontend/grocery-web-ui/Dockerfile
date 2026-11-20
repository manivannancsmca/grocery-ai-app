# Development image for the React dev server (Vite with hot reload).
# Docker Compose bind-mounts the source over /app and keeps node_modules in a
# container volume, so no Node.js installation is needed on the host.
FROM node:26.13.1-alpine3.23

WORKDIR /app
RUN chown node:node /app
USER node

COPY --chown=node:node package.json package-lock.json ./
RUN npm ci

COPY --chown=node:node . .

EXPOSE 6421
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
