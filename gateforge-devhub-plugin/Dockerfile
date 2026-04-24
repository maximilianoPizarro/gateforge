FROM registry.access.redhat.com/ubi9/nodejs-20:latest AS builder
USER 0
WORKDIR /opt/app-root/src
RUN mkdir -p /opt/app-root/src/.npm-global/lib && \
    npm config set prefix /opt/app-root/src/.npm-global && \
    npm install -g yarn
ENV PATH=/opt/app-root/src/.npm-global/bin:$PATH
COPY package.json tsconfig.json ./
COPY src/ src/
RUN npm install --ignore-scripts && \
    npx synp --source-file package-lock.json && \
    npx tsc && \
    npm run build && \
    npx @red-hat-developer-hub/cli@latest plugin export --clean --no-install

FROM scratch
LABEL name="gateforge-devhub-plugin" \
      version="0.1.7" \
      summary="GateForge Developer Hub Dynamic Plugin" \
      io.openshift.tags="gateforge,developer-hub,backstage,kuadrant,connectivity-link,dynamic-plugin" \
      url="https://github.com/maximilianoPizarro/gateforge" \
      vendor="GateForge" \
      maintainer="Maximiliano Pizarro <maximiliano.pizarro.5@gmail.com>"
COPY --from=builder /opt/app-root/src/dist-dynamic/ /gateforge-backstage-plugin-catalog-backend-module-gateforge/
