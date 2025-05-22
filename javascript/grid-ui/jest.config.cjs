module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'jsdom',
  testMatch: ['<rootDir>/src/tests/**/*.test.tsx'],
  transform: {
    '^.+\\.(ts|tsx)$': 'babel-jest',
    '^.+\\.(js|jsx)$': 'babel-jest'
  },
  moduleNameMapper: {
    '\\.(css|less|scss|sass)$': '<rootDir>/src/tests/__mocks__/styleMock.js',
    '\\.(jpg|jpeg|png|gif|svg)$': '<rootDir>/src/tests/__mocks__/styleMock.js'
  },
  transformIgnorePatterns: [
    'node_modules/(?!(pretty-ms|parse-ms)/)'
  ],
  setupFilesAfterEnv: ['<rootDir>/src/setupTests.tsx']
};
