FROM golang:1.26-alpine AS build
# или используйте более новую версию, если доступна:
# FROM golang:1.27-alpine AS build
# FROM golang:latest-alpine AS build

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -o userbot ./cmd/...

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /app
COPY --from=build /app/userbot .
EXPOSE 9090
CMD ["./userbot"]