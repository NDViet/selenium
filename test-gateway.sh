#!/bin/bash

# Test script to start Gateway with WebSocket proxy
echo "Starting Selenium Gateway..."

java -cp "bazel-bin/java/src/org/openqa/selenium/grid/gateway/httpd/libhttpd.jar:$(find bazel-bin -name "*.jar" | tr '\n' ':')" \
  org.openqa.selenium.grid.Main gateway \
  --port 4444 \
  --grid-instances "http://localhost:4445,http://localhost:4446"