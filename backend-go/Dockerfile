# syntax=docker/dockerfile:1
ARG GO_IMAGE=golang:1.27.1-bookworm
FROM ${GO_IMAGE} AS toolchain
FROM toolchain AS build
WORKDIR /src
COPY backend-go/go.mod backend-go/go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY backend-go/ ./
ARG TARGETOS=linux
ARG TARGETARCH
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} \
    go build -trimpath -buildvcs=false -ldflags="-s -w" -o /out/personalos ./cmd/personalos

FROM debian:bookworm-slim AS runtime
COPY --from=toolchain /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 update && apt-get -o Acquire::Retries=3 install -y --no-install-recommends ca-certificates curl poppler-utils tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin personalos \
    && mkdir -p /app/data/uploads && chown -R 10001:10001 /app
WORKDIR /app
COPY --from=build /out/personalos /usr/local/bin/personalos
COPY backend/src/main/resources/db/migration/ /app/migrations/
COPY backend-go/THIRD_PARTY_NOTICES.md /usr/share/doc/personalos/
COPY backend-go/licenses/ /usr/share/doc/personalos/licenses/
ENV TZ=UTC SERVER_PORT=8081 MANAGEMENT_SERVER_PORT=9091 GO_DOTENV_ENABLED=false
USER 10001:10001
EXPOSE 8081 9091
STOPSIGNAL SIGTERM
# management health works for all five roles; readyz remains a separate release gate.
HEALTHCHECK --interval=15s --timeout=4s --start-period=45s --retries=3 \
    CMD ["curl", "--fail", "--silent", "--max-time", "3", "http://127.0.0.1:9091/actuator/health"]
ENTRYPOINT ["/usr/local/bin/personalos"]
CMD ["--foundation", "--p8", "--container", "--migrations", "/app/migrations"]
