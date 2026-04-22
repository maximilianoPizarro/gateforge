FROM registry.access.redhat.com/ubi9/nginx-124:1
COPY nginx.conf "${NGINX_CONF_PATH}"
COPY dist/frontend/browser/ /opt/app-root/src/
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
