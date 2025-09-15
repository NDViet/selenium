package org.openqa.selenium.grid.gateway;

import java.net.URI;
import java.util.Map;
import java.util.logging.Logger;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

/** Handles Grid instance registration requests. */
public class RegistrationHandler implements HttpHandler {

  private static final Logger LOG = Logger.getLogger(RegistrationHandler.class.getName());
  private final GridInstanceRegistry registry;
  private final Json json = new Json();

  public RegistrationHandler(GridInstanceRegistry registry) {
    this.registry = registry;
  }

  @Override
  public HttpResponse execute(HttpRequest req) {
    String path = req.getUri();

    if (path.equals("/register") && req.getMethod().toString().equals("POST")) {
      return handleRegistration(req);
    } else if (path.startsWith("/unregister/") && req.getMethod().toString().equals("DELETE")) {
      return handleUnregistration(req);
    }

    return new HttpResponse().setStatus(404);
  }

  private HttpResponse handleRegistration(HttpRequest req) {
    try {
      String body = Contents.string(req);
      @SuppressWarnings("unchecked")
      Map<String, String> data = json.toType(body, Map.class);

      String instanceId = data.get("instanceId");
      String baseUri = data.get("baseUri");

      if (instanceId != null && baseUri != null) {
        registry.addGridInstance(instanceId, URI.create(baseUri));
        LOG.info("Registered Grid instance: " + instanceId + " at " + baseUri);

        return new HttpResponse()
            .setStatus(200)
            .setContent(Contents.utf8String("{\"status\":\"registered\"}"));
      }

      return new HttpResponse().setStatus(400);

    } catch (Exception e) {
      LOG.warning("Registration failed: " + e.getMessage());
      return new HttpResponse().setStatus(500);
    }
  }

  private HttpResponse handleUnregistration(HttpRequest req) {
    try {
      String path = req.getUri();
      String instanceId = path.substring("/unregister/".length());

      registry.removeGridInstance(instanceId);
      LOG.info("Unregistered Grid instance: " + instanceId);

      return new HttpResponse()
          .setStatus(200)
          .setContent(Contents.utf8String("{\"status\":\"unregistered\"}"));

    } catch (Exception e) {
      LOG.warning("Unregistration failed: " + e.getMessage());
      return new HttpResponse().setStatus(500);
    }
  }
}
