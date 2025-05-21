//
//

import * as React from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import RunningSessions from '../../components/RunningSessions/RunningSessions'
import { BrowserRouter } from 'react-router-dom'

const localStorageMock = {
  getItem: jest.fn(),
  setItem: jest.fn(),
  clear: jest.fn()
}
Object.defineProperty(window, 'localStorage', { value: localStorageMock })

const session = {
  id: 'c9639fea37d962b9ea9f7c8aa66def0d',
  capabilities: JSON.stringify({
    browserName: 'chrome',
    browserVersion: '89.0.4389.82',
    platformName: 'windows',
    customCapability1: 'value1',
    customCapability2: 'value2'
  }),
  startTime: '2023-03-20T15:33:11.6547124Z',
  uri: '/wd/hub/session/c9639fea37d962b9ea9f7c8aa66def0d',
  nodeId: 'node-id',
  nodeUri: 'http://localhost:4444',
  sessionDurationMillis: 0,
  slot: {
    id: 'slot-id',
    stereotype: {
      browserName: 'chrome',
      platformName: 'windows'
    }
  }
}

describe('RunningSessions with dynamic columns', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    localStorageMock.getItem.mockImplementation(key => {
      if (key === 'selenium-grid-selected-columns') {
        return null
      }
      return null
    })
  })

  it('renders the column selector button', () => {
    render(
      <BrowserRouter>
        <RunningSessions sessions={[session]} origin="http://localhost:4444" />
      </BrowserRouter>
    )
    expect(screen.getByLabelText('select columns')).toBeInTheDocument()
  })

  it('renders default columns without selected dynamic columns', () => {
    render(
      <BrowserRouter>
        <RunningSessions sessions={[session]} origin="http://localhost:4444" />
      </BrowserRouter>
    )
    
    const headers = screen.getAllByRole('columnheader')
    expect(headers[0]).toHaveTextContent('Session')
    expect(headers[1]).toHaveTextContent('Capabilities')
    expect(headers[2]).toHaveTextContent('Start time')
    expect(headers[3]).toHaveTextContent('Duration')
    expect(headers[4]).toHaveTextContent('VNC')
    expect(headers[5]).toHaveTextContent('Node URI')
    
    expect(headers.length).toBe(6)
  })

  it('renders dynamic columns when selected from localStorage', () => {
    localStorageMock.getItem.mockImplementation(key => {
      if (key === 'selenium-grid-selected-columns') {
        return JSON.stringify(['browserName', 'platformName'])
      }
      return null
    })
    
    render(
      <BrowserRouter>
        <RunningSessions sessions={[session]} origin="http://localhost:4444" />
      </BrowserRouter>
    )
    
    const headers = screen.getAllByRole('columnheader')
    expect(headers[0]).toHaveTextContent('Session')
    expect(headers[1]).toHaveTextContent('Capabilities')
    
    expect(headers[6]).toHaveTextContent('browserName')
    expect(headers[7]).toHaveTextContent('platformName')
    
    const rows = screen.getAllByRole('row')
    const cells = within(rows[1]).getAllByRole('cell')
    expect(cells[6]).toHaveTextContent('chrome')
    expect(cells[7]).toHaveTextContent('windows')
  })

  it('saves selected columns to localStorage when columns are selected', async () => {
    render(
      <BrowserRouter>
        <RunningSessions sessions={[session]} origin="http://localhost:4444" />
      </BrowserRouter>
    )
    
    const user = userEvent.setup()
    await user.click(screen.getByLabelText('select columns'))
    
    await user.click(screen.getByLabelText('browserName'))
    
    await user.click(screen.getByText('Apply'))
    
    expect(localStorageMock.setItem).toHaveBeenCalledWith(
      'selenium-grid-selected-columns',
      JSON.stringify(['browserName'])
    )
  })

  it('handles empty capability values gracefully', () => {
    localStorageMock.getItem.mockImplementation(key => {
      if (key === 'selenium-grid-selected-columns') {
        return JSON.stringify(['nonExistentCapability'])
      }
      return null
    })
    
    render(
      <BrowserRouter>
        <RunningSessions sessions={[session]} origin="http://localhost:4444" />
      </BrowserRouter>
    )
    
    const headers = screen.getAllByRole('columnheader')
    expect(headers[6]).toHaveTextContent('nonExistentCapability')
    
    const rows = screen.getAllByRole('row')
    const cells = within(rows[1]).getAllByRole('cell')
    expect(cells[6]).toHaveTextContent('')
  })
})
