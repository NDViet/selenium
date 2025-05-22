import React from 'react'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

const renderWithRouter = (ui, { route = '/' } = {}) => {
  return render(
    <MemoryRouter initialEntries={[route]}>
      {ui}
    </MemoryRouter>
  )
}

export * from '@testing-library/react'

export { renderWithRouter as render }
