FROM registry.access.redhat.com/ubi9/nodejs-20:latest AS builder
USER 0
WORKDIR /opt/app-root/src
RUN mkdir -p /opt/app-root/src/.npm-global/lib && \
    npm config set prefix /opt/app-root/src/.npm-global && \
    npm install -g yarn
ENV PATH=/opt/app-root/src/.npm-global/bin:$PATH
COPY package.json package-lock.json* ./
RUN npm ci --legacy-peer-deps --ignore-scripts 2>/dev/null || npm install --legacy-peer-deps --ignore-scripts
RUN npx synp --source-file package-lock.json || true
COPY . .
RUN npx tsc && \
    npm run build && \
    npx @red-hat-developer-hub/cli@latest plugin export --clean --no-install

FROM scratch
LABEL com.redhat.rhdh.plugins='[{"name":"@gateforge/backstage-plugin-gateforge-frontend","version":"0.1.7","description":"GateForge frontend plugin — Observability, Topology, Component Editor tabs"}]'
COPY --from=builder /opt/app-root/src/dist-dynamic/ /gateforge-backstage-plugin-gateforge-frontend/
