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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents match criteria for routing rules in the Gateway. Supports matching on platformName,
 * browserName, browserVersion, and other capabilities.
 */
public class MatchCriteria {

  private final List<String> platformName;
  private final List<String> browserName;
  private final List<String> browserVersion;
  private final Map<String, Object> additionalCapabilities;

  public MatchCriteria(
      List<String> platformName,
      List<String> browserName,
      List<String> browserVersion,
      Map<String, Object> additionalCapabilities) {
    this.platformName = platformName;
    this.browserName = browserName;
    this.browserVersion = browserVersion;
    this.additionalCapabilities = additionalCapabilities;
  }

  public List<String> getPlatformName() {
    return platformName;
  }

  public List<String> getBrowserName() {
    return browserName;
  }

  public List<String> getBrowserVersion() {
    return browserVersion;
  }

  public Map<String, Object> getAdditionalCapabilities() {
    return additionalCapabilities;
  }

  /**
   * Check if this criteria matches the given capabilities.
   *
   * @param capabilities The capabilities to match against
   * @return true if the capabilities match this criteria
   */
  public boolean matches(Map<String, Object> capabilities) {
    // If this is an empty match criteria (default fallback), it matches everything
    if (isEmpty()) {
      return true;
    }

    // Check platformName match
    if (platformName != null && !platformName.isEmpty()) {
      Object platform = capabilities.get("platformName");
      if (platform == null || !platformName.contains(platform.toString())) {
        return false;
      }
    }

    // Check browserName match
    if (browserName != null && !browserName.isEmpty()) {
      Object browser = capabilities.get("browserName");
      if (browser == null || !browserName.contains(browser.toString())) {
        return false;
      }
    }

    // Check browserVersion match
    if (browserVersion != null && !browserVersion.isEmpty()) {
      Object version = capabilities.get("browserVersion");
      if (version == null || !browserVersion.contains(version.toString())) {
        return false;
      }
    }

    // Check additional capabilities
    if (additionalCapabilities != null && !additionalCapabilities.isEmpty()) {
      for (Map.Entry<String, Object> entry : additionalCapabilities.entrySet()) {
        Object actualValue = capabilities.get(entry.getKey());
        if (!Objects.equals(actualValue, entry.getValue())) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Check if this is an empty criteria (matches everything).
   *
   * @return true if this criteria is empty
   */
  public boolean isEmpty() {
    return (platformName == null || platformName.isEmpty())
        && (browserName == null || browserName.isEmpty())
        && (browserVersion == null || browserVersion.isEmpty())
        && (additionalCapabilities == null || additionalCapabilities.isEmpty());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MatchCriteria that = (MatchCriteria) o;
    return Objects.equals(platformName, that.platformName)
        && Objects.equals(browserName, that.browserName)
        && Objects.equals(browserVersion, that.browserVersion)
        && Objects.equals(additionalCapabilities, that.additionalCapabilities);
  }

  @Override
  public int hashCode() {
    return Objects.hash(platformName, browserName, browserVersion, additionalCapabilities);
  }

  @Override
  public String toString() {
    return "MatchCriteria{"
        + "platformName="
        + platformName
        + ", browserName="
        + browserName
        + ", browserVersion="
        + browserVersion
        + ", additionalCapabilities="
        + additionalCapabilities
        + '}';
  }
}
