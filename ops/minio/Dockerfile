# The official community image repositories are unavailable. Preserve the
# deployed release by compiling its exact upstream source commit.
FROM golang:1.27.1-alpine AS server-build
RUN apk add --no-cache git
WORKDIR /src
RUN git init && git remote add origin https://github.com/minio/minio.git \
    && git fetch --depth 1 origin 0d7408fc9969caf07de6a8c3a84f9fbb10a6739e \
    && git checkout --detach FETCH_HEAD
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
RUN go build -trimpath -ldflags="-s -w" -o /out/minio .

FROM golang:1.27.1-alpine AS client-build
RUN apk add --no-cache git
WORKDIR /src
RUN git init && git remote add origin https://github.com/minio/mc.git \
    && git fetch --depth 1 origin b00526b153a31b36767991a4f5ce2cced435ee8e \
    && git checkout --detach FETCH_HEAD
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
RUN go build -trimpath -ldflags="-s -w" -o /out/mc .

FROM alpine:3.23 AS server
RUN apk add --no-cache ca-certificates && mkdir /data && chown 10001:10001 /data
COPY --from=server-build /out/minio /usr/local/bin/minio
COPY --from=server-build /src/LICENSE /src/NOTICE /usr/share/licenses/minio/
USER 10001:10001
EXPOSE 9000 9001
ENTRYPOINT ["minio"]

FROM alpine:3.23 AS client
RUN apk add --no-cache ca-certificates
COPY --from=client-build /out/mc /usr/local/bin/mc
COPY --from=client-build /src/LICENSE /src/NOTICE /usr/share/licenses/mc/
ENV MC_CONFIG_DIR=/tmp/mc
USER 10001:10001
ENTRYPOINT ["mc"]
