FROM registry.access.redhat.com/ubi9/nodejs-20:latest AS builder
USER 0
WORKDIR /opt/app-root/src
COPY package.json tsconfig.json ./
COPY src/ src/
RUN npm install && npm run build

FROM registry.access.redhat.com/ubi9/nodejs-20-minimal:latest
USER 1001
WORKDIR /opt/app-root/src
COPY --from=builder /opt/app-root/src/dist/ dist/
COPY --from=builder /opt/app-root/src/node_modules/ node_modules/
COPY --from=builder /opt/app-root/src/package.json .
EXPOSE 7007
CMD ["node", "dist/index.js"]
