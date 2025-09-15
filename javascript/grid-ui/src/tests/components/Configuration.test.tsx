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

import React from 'react'
import { render, screen } from '@testing-library/react'
import Configuration from '../../screens/Configuration/Configuration'

// Mock fetch
global.fetch = jest.fn()

describe('Configuration', () => {
  beforeEach(() => {
    (fetch as jest.Mock).mockClear()
  })

  it('renders configuration page', async () => {
    (fetch as jest.Mock)
      .mockResolvedValueOnce({
        ok: true,
        text: async () => '# No routing rules configured\n'
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ instances: [] })
      })

    render(<Configuration />)
    
    // Wait for loading to complete
    await screen.findByText('Configuration')
    
    expect(screen.getByText('Configuration')).toBeInTheDocument()
    expect(screen.getByText('Routing Rules (YAML)')).toBeInTheDocument()
    expect(screen.getByText('Grid Instances')).toBeInTheDocument()
    expect(screen.getByText('Add Instance')).toBeInTheDocument()
  })

  it('shows loading state initially', () => {
    (fetch as jest.Mock).mockImplementation(() => new Promise(() => {}))

    render(<Configuration />)
    
    expect(screen.getByRole('progressbar')).toBeInTheDocument()
  })
})