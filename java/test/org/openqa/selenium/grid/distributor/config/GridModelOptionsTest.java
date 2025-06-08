//
//

package org.openqa.selenium.grid.distributor.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.grid.config.MapConfig;
import org.openqa.selenium.grid.distributor.storage.GridModelStorage;
import org.openqa.selenium.grid.distributor.storage.local.LocalGridModelStorage;

class GridModelOptionsTest {

  @Test
  void shouldCreateLocalGridModelStorageByDefault() {
    GridModelOptions options = new GridModelOptions(new MapConfig(Map.of()));
    
    GridModelStorage storage = options.getGridModelStorage();
    
    assertThat(storage).isInstanceOf(LocalGridModelStorage.class);
  }

  @Test
  void shouldCreateLocalGridModelStorageWhenExplicitlyConfigured() {
    Map<String, Object> config = Map.of(
        "gridmodel", Map.of(
            "implementation", "org.openqa.selenium.grid.distributor.storage.local.LocalGridModelStorage"
        )
    );
    
    GridModelOptions options = new GridModelOptions(new MapConfig(config));
    
    GridModelStorage storage = options.getGridModelStorage();
    
    assertThat(storage).isInstanceOf(LocalGridModelStorage.class);
  }

  @Test
  void shouldCreateRedisGridModelStorageWhenConfigured() {
    Map<String, Object> config = Map.of(
        "gridmodel", Map.of(
            "implementation", "org.openqa.selenium.grid.distributor.storage.redis.RedisGridModelStorage"
        ),
        "sessions", Map.of(
            "map", "org.openqa.selenium.grid.sessionmap.redis.RedisBackedSessionMap",
            "redis-uri", "redis://localhost:6379"
        )
    );
    
    GridModelOptions options = new GridModelOptions(new MapConfig(config));
    
    GridModelStorage storage = options.getGridModelStorage();
    
    assertThat(storage.getClass().getSimpleName()).isEqualTo("RedisGridModelStorage");
  }

  @Test
  void shouldCreateJdbcGridModelStorageWhenConfigured() {
    Map<String, Object> config = Map.of(
        "gridmodel", Map.of(
            "implementation", "org.openqa.selenium.grid.distributor.storage.jdbc.JdbcGridModelStorage"
        ),
        "sessions", Map.of(
            "map", "org.openqa.selenium.grid.sessionmap.jdbc.JdbcBackedSessionMap",
            "jdbc-url", "jdbc:h2:mem:test",
            "jdbc-user", "sa",
            "jdbc-password", ""
        )
    );
    
    GridModelOptions options = new GridModelOptions(new MapConfig(config));
    
    GridModelStorage storage = options.getGridModelStorage();
    
    assertThat(storage.getClass().getSimpleName()).isEqualTo("JdbcGridModelStorage");
  }

  @Test
  void shouldUseReflectionForCustomImplementation() {
    Map<String, Object> config = Map.of(
        "gridmodel", Map.of(
            "implementation", "org.openqa.selenium.grid.distributor.storage.local.LocalGridModelStorage"
        )
    );
    
    GridModelOptions options = new GridModelOptions(new MapConfig(config));
    
    GridModelStorage storage = options.getGridModelStorage();
    
    assertThat(storage).isInstanceOf(LocalGridModelStorage.class);
  }
}
