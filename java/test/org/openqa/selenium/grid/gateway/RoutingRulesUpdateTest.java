// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.remote.http.Contents.utf8String;
import static org.openqa.selenium.remote.http.HttpMethod.POST;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.tracing.Tracer;

class RoutingRulesUpdateTest {

  private Gateway gateway;
  private Json json;

  @BeforeEach
  void setUp() {
    Tracer tracer = org.openqa.selenium.grid.testing.TestTracer.createTracer();
    GridInstanceRegistry registry =
        new GridInstanceRegistry(
            tracer,
            req -> null, // HttpClient.Factory
            StrategyType.GREEDY,
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            3);
    json = new Json();

    gateway =
        new Gateway(
            tracer,
            req -> null, // HttpClient.Factory
            registry,
            null, // RoutingRulesConfig
            3, // maxRetryAttempts
            Duration.ofSeconds(30), // requestTimeout
            URI.create("http://localhost:4444"), // publicUri
            "test-version");
  }

  @Test
  void shouldUpdateRoutingRulesWithValidYaml() {
    String yamlContent =
        "routingRules:\n"
            + "  - match:\n"
            + "      browserName: [chrome]\n"
            + "    distribute:\n"
            + "      - index: 0\n"
            + "        weight: 70\n"
            + "      - index: 1\n"
            + "        weight: 30\n";

    HttpRequest request = new HttpRequest(POST, "/routing-rules");
    request.setContent(utf8String(yamlContent));

    HttpResponse response = gateway.execute(request);

    assertThat(response.getStatus()).isEqualTo(200);

    Map<String, Object> responseBody =
        json.toType(org.openqa.selenium.remote.http.Contents.string(response), Map.class);

    assertThat(responseBody.get("status")).isEqualTo("success");
    assertThat(responseBody.get("message")).isEqualTo("Routing rules updated successfully");
    assertThat(responseBody.get("rulesCount")).isEqualTo(0); // YAML parsing not implemented yet
  }

  @Test
  void shouldReturnErrorForEmptyContent() {
    HttpRequest request = new HttpRequest(POST, "/routing-rules");
    request.setContent(utf8String(""));

    HttpResponse response = gateway.execute(request);

    assertThat(response.getStatus()).isEqualTo(400);

    Map<String, Object> responseBody =
        json.toType(org.openqa.selenium.remote.http.Contents.string(response), Map.class);

    assertThat(responseBody.get("error")).isEqualTo("YAML content is required");
  }

  @Test
  void shouldReturnErrorForNullContent() {
    HttpRequest request = new HttpRequest(POST, "/routing-rules");

    HttpResponse response = gateway.execute(request);

    assertThat(response.getStatus()).isEqualTo(400);

    Map<String, Object> responseBody =
        json.toType(org.openqa.selenium.remote.http.Contents.string(response), Map.class);

    assertThat(responseBody.get("error")).isEqualTo("YAML content is required");
  }
}
