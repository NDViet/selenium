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

const originalError = console.error;
console.error = (...args) => {
  if (/Warning.*not wrapped in act/.test(args[0])) {
    return;
  }
  if (args[0] && typeof args[0] === 'string' && args[0].includes('An error occurred! For more details')) {
    return;
  }
  if (args[0] && typeof args[0] === 'string' && args[0].includes('Cache data may be lost when replacing')) {
    return;
  }
  originalError.call(console, ...args);
};

const originalLog = console.log;
console.log = (...args) => {
  if (args[0] instanceof Error && args[0].message && args[0].message.includes('Invalid URL')) {
    return;
  }
  originalLog.call(console, ...args);
};
