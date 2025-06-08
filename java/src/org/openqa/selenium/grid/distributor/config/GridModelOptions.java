//
//

package org.openqa.selenium.grid.distributor.config;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.distributor.storage.GridModelStorage;
import org.openqa.selenium.grid.distributor.storage.jdbc.JdbcGridModelStorage;
import org.openqa.selenium.grid.distributor.storage.local.LocalGridModelStorage;
import org.openqa.selenium.grid.distributor.storage.redis.RedisGridModelStorage;
import org.openqa.selenium.grid.sessionmap.config.SessionMapOptions;
import org.openqa.selenium.grid.sessionmap.jdbc.JdbcSessionMapOptions;
import org.openqa.selenium.internal.Require;

public class GridModelOptions {

  private static final String GRIDMODEL_SECTION = "gridmodel";
  private static final String DEFAULT_GRIDMODEL_IMPLEMENTATION =
      "org.openqa.selenium.grid.distributor.storage.local.LocalGridModelStorage";

  private final Config config;

  public GridModelOptions(Config config) {
    this.config = Require.nonNull("Config", config);
  }

  public GridModelStorage getGridModelStorage() {
    String implementation = config.get(GRIDMODEL_SECTION, "implementation")
        .orElse(DEFAULT_GRIDMODEL_IMPLEMENTATION);

    switch (implementation) {
      case "org.openqa.selenium.grid.distributor.storage.local.LocalGridModelStorage":
        return new LocalGridModelStorage();

      case "org.openqa.selenium.grid.distributor.storage.redis.RedisGridModelStorage":
        URI redisUri = new SessionMapOptions(config).getSessionMapUri();
        return new RedisGridModelStorage(redisUri);

      case "org.openqa.selenium.grid.distributor.storage.jdbc.JdbcGridModelStorage":
        try {
          JdbcSessionMapOptions jdbcOptions = new JdbcSessionMapOptions(config);
          Connection connection = jdbcOptions.getJdbcConnection();
          return new JdbcGridModelStorage(connection);
        } catch (SQLException e) {
          throw new RuntimeException("Failed to create JDBC GridModel storage", e);
        }

      default:
        return config.getClass(
            GRIDMODEL_SECTION,
            "implementation",
            GridModelStorage.class,
            DEFAULT_GRIDMODEL_IMPLEMENTATION);
    }
  }
}
