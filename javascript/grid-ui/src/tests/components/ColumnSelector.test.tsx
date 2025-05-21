//
//

import * as React from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ColumnSelector from '../../components/RunningSessions/ColumnSelector'

const localStorageMock = {
  getItem: jest.fn(),
  setItem: jest.fn(),
  clear: jest.fn()
}
Object.defineProperty(window, 'localStorage', { value: localStorageMock })

const sessions = [
  {
    id: 'session1',
    capabilities: JSON.stringify({
      browserName: 'chrome',
      browserVersion: '89.0.4389.82',
      platformName: 'windows',
      customCapability1: 'value1',
      customCapability2: 'value2'
    })
  },
  {
    id: 'session2',
    capabilities: JSON.stringify({
      browserName: 'firefox',
      browserVersion: '86.0',
      platformName: 'linux',
      customCapability3: 'value3'
    })
  }
]

describe('ColumnSelector Component', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    localStorageMock.getItem.mockReturnValue(null)
  })

  it('renders the column selector button', () => {
    render(
      <ColumnSelector
        sessions={sessions}
        selectedColumns={[]}
        onColumnSelectionChange={() => {}}
      />
    )
    expect(screen.getByRole('button')).toBeInTheDocument()
    expect(screen.getByLabelText('select columns')).toBeInTheDocument()
  })

  it('opens dialog when button is clicked', async () => {
    render(
      <ColumnSelector
        sessions={sessions}
        selectedColumns={[]}
        onColumnSelectionChange={() => {}}
      />
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button'))
    
    expect(screen.getByText('Select Columns to Display')).toBeInTheDocument()
    expect(screen.getByText('Select capability fields to display as additional columns:')).toBeInTheDocument()
  })

  it('shows available capability keys from sessions', async () => {
    render(
      <ColumnSelector
        sessions={sessions}
        selectedColumns={[]}
        onColumnSelectionChange={() => {}}
      />
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button'))
    
    expect(screen.getByLabelText('browserName')).toBeInTheDocument()
    expect(screen.getByLabelText('browserVersion')).toBeInTheDocument()
    expect(screen.getByLabelText('platformName')).toBeInTheDocument()
    expect(screen.getByLabelText('customCapability1')).toBeInTheDocument()
    expect(screen.getByLabelText('customCapability2')).toBeInTheDocument()
    expect(screen.getByLabelText('customCapability3')).toBeInTheDocument()
  })

  it('loads selected columns from props', async () => {
    const selectedColumns = ['browserName', 'customCapability1']
    render(
      <ColumnSelector
        sessions={sessions}
        selectedColumns={selectedColumns}
        onColumnSelectionChange={() => {}}
      />
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button'))
    
    const browserNameCheckbox = screen.getByLabelText('browserName') as HTMLInputElement
    const customCapability1Checkbox = screen.getByLabelText('customCapability1') as HTMLInputElement
    const platformNameCheckbox = screen.getByLabelText('platformName') as HTMLInputElement
    
    expect(browserNameCheckbox.checked).toBe(true)
    expect(customCapability1Checkbox.checked).toBe(true)
    expect(platformNameCheckbox.checked).toBe(false)
  })

  it('calls onColumnSelectionChange when Apply button is clicked', async () => {
    const onColumnSelectionChangeMock = jest.fn()
    render(
      <ColumnSelector
        sessions={sessions}
        selectedColumns={[]}
        onColumnSelectionChange={onColumnSelectionChangeMock}
      />
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button'))
    
    await user.click(screen.getByLabelText('browserName'))
    
    await user.click(screen.getByText('Apply'))
    
    expect(onColumnSelectionChangeMock).toHaveBeenCalledWith(['browserName'])
  })

  it('closes dialog when Cancel button is clicked', async () => {
    const onColumnSelectionChangeMock = jest.fn()
    render(
      <ColumnSelector
        sessions={sessions}
        selectedColumns={[]}
        onColumnSelectionChange={onColumnSelectionChangeMock}
      />
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button'))
    
    await user.click(screen.getByText('Cancel'))
    
    expect(screen.queryByText('Select Columns to Display')).not.toBeInTheDocument()
    expect(onColumnSelectionChangeMock).not.toHaveBeenCalled()
  })

  it('selects all columns when "Select All" is clicked', async () => {
    render(
      <ColumnSelector
        sessions={sessions}
        selectedColumns={[]}
        onColumnSelectionChange={() => {}}
      />
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button'))
    
    await user.click(screen.getByLabelText('Select All / Unselect All'))
    
    const checkboxes = screen.getAllByRole('checkbox').slice(1) // Skip the "Select All" checkbox
    checkboxes.forEach(checkbox => {
      expect(checkbox).toBeChecked()
    })
  })

  it('unselects all columns when "Unselect All" is clicked', async () => {
    render(
      <ColumnSelector
        sessions={sessions}
        selectedColumns={['browserName', 'browserVersion', 'platformName']}
        onColumnSelectionChange={() => {}}
      />
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button'))
    
    await user.click(screen.getByLabelText('Select All / Unselect All'))
    
    const checkboxes = screen.getAllByRole('checkbox').slice(1) // Skip the "Select All" checkbox
    checkboxes.forEach(checkbox => {
      expect(checkbox).not.toBeChecked()
    })
  })

  it('loads capability keys from localStorage', async () => {
    localStorageMock.getItem.mockImplementation(key => {
      if (key === 'selenium-grid-all-capability-keys') {
        return JSON.stringify(['savedKey1', 'savedKey2'])
      }
      return null
    })
    
    render(
      <ColumnSelector
        sessions={[]} // Empty sessions
        selectedColumns={[]}
        onColumnSelectionChange={() => {}}
      />
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button'))
    
    expect(screen.getByLabelText('savedKey1')).toBeInTheDocument()
    expect(screen.getByLabelText('savedKey2')).toBeInTheDocument()
  })

  it('saves capability keys to localStorage', () => {
    render(
      <ColumnSelector
        sessions={sessions}
        selectedColumns={[]}
        onColumnSelectionChange={() => {}}
      />
    )
    
    expect(localStorageMock.setItem).toHaveBeenCalledWith(
      'selenium-grid-all-capability-keys',
      expect.any(String)
    )
    
    const savedKeys = JSON.parse(localStorageMock.setItem.mock.calls[0][1])
    expect(savedKeys).toContain('browserName')
    expect(savedKeys).toContain('browserVersion')
    expect(savedKeys).toContain('platformName')
    expect(savedKeys).toContain('customCapability1')
    expect(savedKeys).toContain('customCapability2')
    expect(savedKeys).toContain('customCapability3')
  })
})
