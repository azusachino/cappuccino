FROM artifacts.iflytek.com/docker-private/scv/alpine:3.11
WORKDIR /app
ADD ./build/native/nativeCompile/cappuccino /app/cappuccino
RUN ["chmod", "+x", "/app/cappuccino"]
CMD ["/app/cappuccino"]
