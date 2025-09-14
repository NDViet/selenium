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

package org.openqa.selenium.grid.distributor.redis;

import java.time.Duration;
import java.util.UUID;
import org.openqa.selenium.redis.GridRedisClient;

/**
 * Distributed lock implementation using Redis to prevent race conditions
 * between multiple distributor replicas.
 */
public class RedisDistributedLock {
  
  private static final String LOCK_PREFIX = "distributor:lock:";
  private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);
  
  private final GridRedisClient redis;
  private final String lockId;
  
  public RedisDistributedLock(GridRedisClient redis) {
    this.redis = redis;
    this.lockId = UUID.randomUUID().toString();
  }
  
  public boolean tryLock(String resource) {
    return tryLock(resource, DEFAULT_LOCK_TIMEOUT);
  }
  
  public boolean tryLock(String resource, Duration timeout) {
    String lockKey = LOCK_PREFIX + resource;
    String result = redis.getConnection().sync()
        .set(lockKey, lockId, io.lettuce.core.SetArgs.Builder.px(timeout.toMillis()).nx());
    return "OK".equals(result);
  }
  
  public void unlock(String resource) {
    String lockKey = LOCK_PREFIX + resource;
    String script = 
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('del', KEYS[1]) " +
        "else " +
        "  return 0 " +
        "end";
    redis.getConnection().sync().eval(script, io.lettuce.core.ScriptOutputType.INTEGER, new String[]{lockKey}, lockId);
  }
}