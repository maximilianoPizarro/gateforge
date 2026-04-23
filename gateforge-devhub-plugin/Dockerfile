FROM registry.access.redhat.com/ubi9/nodejs-20:latest AS builder
USER 0
WORKDIR /opt/app-root/src
COPY package.json tsconfig.json ./
COPY src/ src/
RUN npm install && npm run build

FROM registry.access.redhat.com/ubi9/nodejs-20-minimal:latest
LABEL name="gateforge-devhub-plugin" \
      version="0.1.7" \
      summary="GateForge Developer Hub Plugin" \
      description="Standalone webhook-based plugin for Red Hat Developer Hub that receives migration events from GateForge and manages Backstage catalog entities" \
      io.k8s.display-name="GateForge DevHub Plugin" \
      io.k8s.description="Receives migration events from GateForge backend and generates Backstage Component entities linked to Kuadrant API resources" \
      io.openshift.tags="gateforge,developer-hub,backstage,kuadrant,connectivity-link" \
      url="https://github.com/maximilianoPizarro/gateforge" \
      vendor="GateForge" \
      maintainer="Maximiliano Pizarro <maximiliano.pizarro.5@gmail.com>"
USER 1001
WORKDIR /opt/app-root/src
COPY --from=builder /opt/app-root/src/dist/ dist/
COPY --from=builder /opt/app-root/src/node_modules/ node_modules/
COPY --from=builder /opt/app-root/src/package.json .
EXPOSE 7007
CMD ["node", "dist/index.js"]
