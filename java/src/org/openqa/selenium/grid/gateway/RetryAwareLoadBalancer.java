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
import java.util.Optional;
import java.util.logging.Logger;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.internal.Require;

/**
 * Retry-aware load balancer that forces fallback to alternative instances on final retry. Wraps
 * another load balancing strategy and provides retry logic with forced fallback.
 */
public class RetryAwareLoadBalancer implements LoadBalancingStrategy {

  private static final Logger LOG = Logger.getLogger(RetryAwareLoadBalancer.class.getName());

  private final LoadBalancingStrategy delegate;
  private final boolean forceFallbackOnFinalRetry;
  private final ThreadLocal<Integer> currentRetryCount = new ThreadLocal<>();
  private final ThreadLocal<GridInstance> previousFailedInstance = new ThreadLocal<>();

  public RetryAwareLoadBalancer(LoadBalancingStrategy delegate, boolean forceFallbackOnFinalRetry) {
    this.delegate = Require.nonNull("Delegate strategy", delegate);
    this.forceFallbackOnFinalRetry = forceFallbackOnFinalRetry;
  }

  @Override
  public Optional<GridInstance> selectGridInstance(
      List<GridInstance> availableInstances, Capabilities requestedCapabilities) {

    if (availableInstances.isEmpty()) {
      return Optional.empty();
    }

    Integer retryCount = currentRetryCount.get();
    GridInstance failedInstance = previousFailedInstance.get();

    // If this is the final retry and force fallback is enabled
    if (forceFallbackOnFinalRetry
        && retryCount != null
        && retryCount > 0
        && failedInstance != null) {
      LOG.info(
          "Final retry attempt - forcing fallback to different instance (may break rule-based"
              + " routing)");

      // Remove the previously failed instance from available options
      List<GridInstance> alternativeInstances =
          availableInstances.stream()
              .filter(instance -> !instance.getId().equals(failedInstance.getId()))
              .collect(java.util.stream.Collectors.toList());

      if (!alternativeInstances.isEmpty()) {
        // Use simple round-robin for fallback to avoid rule complexity
        GridInstance selected = alternativeInstances.get(0);
        LOG.info(
            "Forced fallback selected instance: "
                + selected.getId()
                + " (avoiding failed instance: "
                + failedInstance.getId()
                + ")");
        return Optional.of(selected);
      } else {
        LOG.warning("No alternative instances available for forced fallback");
      }
    }

    // Normal selection using delegate strategy
    return delegate.selectGridInstance(availableInstances, requestedCapabilities);
  }

  @Override
  public String getStrategyName() {
    return delegate.getStrategyName() + "+RetryAware";
  }

  /** Set the current retry count for this thread. */
  public void setRetryCount(int retryCount) {
    currentRetryCount.set(retryCount);
  }

  /** Set the previously failed instance for this thread. */
  public void setPreviousFailedInstance(GridInstance failedInstance) {
    previousFailedInstance.set(failedInstance);
  }

  /** Clear retry context for this thread. */
  public void clearRetryContext() {
    currentRetryCount.remove();
    previousFailedInstance.remove();
  }

  public LoadBalancingStrategy getDelegate() {
    return delegate;
  }
}
