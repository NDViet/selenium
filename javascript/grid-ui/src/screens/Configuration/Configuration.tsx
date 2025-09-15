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

import React, { useState, useEffect } from 'react'
import {
  Box,
  Button,
  Paper,
  TextField,
  Typography,
  Alert,
  CircularProgress,
  Grid,
  IconButton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Checkbox,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Chip,
  LinearProgress,
  Tooltip,
  Card,
  CardContent
} from '@mui/material'
import { 
  Add as AddIcon, 
  Delete as DeleteIcon,
  CheckCircle as HealthyIcon,
  Warning as WarningIcon,
  Error as ErrorIcon,
  Refresh as RefreshIcon
} from '@mui/icons-material'
import { GridConfig } from '../../config'

interface GridInstance {
  id: string
  url: string
  enabled: boolean
}

interface GridInstanceHealthStatus {
  id: string
  baseUri: string
  healthy: boolean
  availableForNewSessions: boolean
  enabled: boolean
  draining: boolean
  unhealthy: boolean
  status: string
  sessionCount: number
  loadFactor: number
  failureCount: number
  lastHealthCheck: string
  createdAt: string
}

function Configuration(): JSX.Element {
  const [yamlContent, setYamlContent] = useState('')
  const [originalContent, setOriginalContent] = useState('')
  const [routingRulesEnabled, setRoutingRulesEnabled] = useState(true)
  const [originalRoutingRulesEnabled, setOriginalRoutingRulesEnabled] = useState(true)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [gridInstances, setGridInstances] = useState<GridInstance[]>([])
  const [originalGridInstances, setOriginalGridInstances] = useState<GridInstance[]>([])
  const [gridInstancesHealth, setGridInstancesHealth] = useState<GridInstanceHealthStatus[]>([])
  const [healthLoading, setHealthLoading] = useState(false)
  const [showAddDialog, setShowAddDialog] = useState(false)
  const [showUpdateDialog, setShowUpdateDialog] = useState(false)
  const [showDeleteDialog, setShowDeleteDialog] = useState(false)
  const [instanceToDelete, setInstanceToDelete] = useState<GridInstance | null>(null)
  const [newInstanceUrl, setNewInstanceUrl] = useState('')
  const [newInstanceId, setNewInstanceId] = useState('')
  const [updating, setUpdating] = useState(false)

  const hasChanges = yamlContent !== originalContent || routingRulesEnabled !== originalRoutingRulesEnabled
  const hasGridChanges = JSON.stringify(gridInstances) !== JSON.stringify(originalGridInstances)

  useEffect(() => {
    fetchRoutingRules()
    fetchGridInstances()
    fetchGridInstancesHealth()
  }, [])

  // Auto-refresh health status every 30 seconds
  useEffect(() => {
    const interval = setInterval(() => {
      fetchGridInstancesHealth()
    }, 30000)
    return () => clearInterval(interval)
  }, [])

  const fetchRoutingRules = async () => {
    try {
      setLoading(true)
      const baseUri = GridConfig.serverUri.replace('/graphql', '')
      const response = await fetch(`${baseUri}/routing-rules`)
      if (response.ok) {
        const content = await response.text()
        setYamlContent(content)
        setOriginalContent(content)
        
        // Parse enabled status from YAML
        const enabledMatch = content.match(/^enabled:\s*(true|false)/m)
        const enabled = enabledMatch ? enabledMatch[1] === 'true' : true
        setRoutingRulesEnabled(enabled)
        setOriginalRoutingRulesEnabled(enabled)
      } else {
        setError('Failed to fetch routing rules')
      }
    } catch (err) {
      setError('Error fetching routing rules: ' + err.message)
    } finally {
      setLoading(false)
    }
  }

  const validateYaml = (content: string): boolean => {
    if (!content.trim()) return true
    
    try {
      const lines = content.split('\n')
      
      for (const line of lines) {
        if (line.trim() === '' || line.trim().startsWith('#')) continue
        
        const leadingSpaces = line.length - line.trimStart().length
        if (leadingSpaces % 2 !== 0) {
          throw new Error('Invalid indentation - must use 2 spaces')
        }
        
        if (line.includes('\t')) {
          throw new Error('Tabs not allowed - use spaces for indentation')
        }
      }
      
      return true
    } catch (err) {
      setError('YAML validation error: ' + err.message)
      return false
    }
  }

  const handleUpdate = async () => {
    if (!validateYaml(yamlContent)) return
    
    try {
      setSaving(true)
      setError('')
      
      const baseUri = GridConfig.serverUri.replace('/graphql', '')
      
      // Update YAML content
      const response = await fetch(`${baseUri}/routing-rules`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-yaml'
        },
        body: yamlContent
      })
      
      if (!response.ok) {
        const errorData = await response.json()
        setError(errorData.error || 'Failed to update routing rules')
        return
      }
      
      // Update enabled status if changed
      if (routingRulesEnabled !== originalRoutingRulesEnabled) {
        const enabledResponse = await fetch(`${baseUri}/routing-rules/enabled`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ enabled: routingRulesEnabled })
        })
        
        if (!enabledResponse.ok) {
          const errorData = await enabledResponse.json()
          setError(errorData.error || 'Failed to update routing rules enabled status')
          return
        }
      }
      
      setOriginalContent(yamlContent)
      setOriginalRoutingRulesEnabled(routingRulesEnabled)
      setSuccess('Routing rules updated successfully')
      setTimeout(() => setSuccess(''), 3000)
      
    } catch (err) {
      setError('Error updating routing rules: ' + err.message)
    } finally {
      setSaving(false)
    }
  }

  const handleDiscard = () => {
    setYamlContent(originalContent)
    setRoutingRulesEnabled(originalRoutingRulesEnabled)
    setError('')
    setSuccess('')
  }

  const fetchGridInstances = async () => {
    try {
      const baseUri = GridConfig.serverUri.replace('/graphql', '')
      const response = await fetch(`${baseUri}/discovery`)
      if (response.ok) {
        const data = await response.json()
        const instances = data.instances || []
        setGridInstances(instances)
        setOriginalGridInstances(JSON.parse(JSON.stringify(instances)))
      } else {
        console.warn('Failed to fetch grid instances')
        setGridInstances([])
      }
    } catch (err) {
      console.warn('Error fetching grid instances:', err)
      setGridInstances([])
    }
  }

  const fetchGridInstancesHealth = async () => {
    try {
      setHealthLoading(true)
      const baseUri = GridConfig.serverUri.replace('/graphql', '')
      const response = await fetch(`${baseUri}/status`)
      if (response.ok) {
        const data = await response.json()
        const healthInstances = data.gridInstances || []
        setGridInstancesHealth(healthInstances)
      } else {
        console.warn('Failed to fetch grid instances health status')
        setGridInstancesHealth([])
      }
    } catch (err) {
      console.warn('Error fetching grid instances health:', err)
      setGridInstancesHealth([])
    } finally {
      setHealthLoading(false)
    }
  }

  const handleAddInstance = async () => {
    if (!newInstanceUrl.trim()) {
      setError('URL is required')
      return
    }

    // Check for duplicate URL
    const duplicateUrl = gridInstances.find(instance => instance.url === newInstanceUrl.trim())
    if (duplicateUrl) {
      setError('Grid instance URL must be unique. This URL is already registered.')
      return
    }

    try {
      const id = newInstanceId.trim() || `grid-${gridInstances.length + 1}`
      const baseUri = GridConfig.serverUri.replace('/graphql', '')
      
      const response = await fetch(`${baseUri}/discovery`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: newInstanceUrl, id })
      })

      if (response.ok) {
        await fetchGridInstances()
        setNewInstanceUrl('')
        setNewInstanceId('')
        setShowAddDialog(false)
        setSuccess('Grid instance added successfully')
        setTimeout(() => setSuccess(''), 3000)
      } else {
        const errorData = await response.json()
        setError(errorData.error || 'Failed to add grid instance')
      }
    } catch (err) {
      setError('Error adding grid instance: ' + err.message)
    }
  }

  const handleRemoveInstance = async (instance: GridInstance, keepConfig: boolean) => {
    try {
      const baseUri = GridConfig.serverUri.replace('/graphql', '')
      
      if (!keepConfig) {
        const response = await fetch(`${baseUri}/discovery?url=${encodeURIComponent(instance.url)}`, {
          method: 'DELETE'
        })
        
        if (!response.ok) {
          const errorData = await response.json()
          setError(errorData.error || 'Failed to remove grid instance')
          return
        }
        
        await fetchGridInstances()
      } else {
        setGridInstances(gridInstances.map(g => 
          g.id === instance.id ? { ...g, enabled: false } : g
        ))
      }
      
      setSuccess(`Grid instance ${keepConfig ? 'disabled' : 'removed'} successfully`)
      setTimeout(() => setSuccess(''), 3000)
    } catch (err) {
      setError('Error removing grid instance: ' + err.message)
    }
  }

  const handleDeleteClick = (instance: GridInstance) => {
    setInstanceToDelete(instance)
    setShowDeleteDialog(true)
  }

  const handleConfirmDelete = async () => {
    if (instanceToDelete) {
      await handleRemoveInstance(instanceToDelete, false)
      setShowDeleteDialog(false)
      setInstanceToDelete(null)
    }
  }

  const handleToggleInstance = (instance: GridInstance) => {
    setGridInstances(gridInstances.map(g => 
      g.id === instance.id ? { ...g, enabled: !g.enabled } : g
    ))
  }

  const handleUpdateInstances = async () => {
    setUpdating(true)
    try {
      const baseUri = GridConfig.serverUri.replace('/graphql', '')
      const changes = []

      for (const instance of gridInstances) {
        const original = originalGridInstances.find(g => g.id === instance.id)
        if (original && instance.enabled !== original.enabled) {
          changes.push(instance)
          
          // Update enabled status
          const response = await fetch(`${baseUri}/discovery/${instance.id}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled: instance.enabled })
          })
          
          if (!response.ok) {
            throw new Error(`Failed to update ${instance.id}`)
          }
        }
      }

      setOriginalGridInstances(JSON.parse(JSON.stringify(gridInstances)))
      setShowUpdateDialog(false)
      setSuccess(`Updated ${changes.length} grid instance(s)`)
      setTimeout(() => setSuccess(''), 3000)
    } catch (err) {
      setError('Error updating grid instances: ' + err.message)
    } finally {
      setUpdating(false)
    }
  }

  const handleDiscardGridChanges = () => {
    setGridInstances(JSON.parse(JSON.stringify(originalGridInstances)))
  }

  // Helper functions for health status visualization
  const getHealthIcon = (health: GridInstanceHealthStatus) => {
    if (health.unhealthy || !health.healthy) {
      return <ErrorIcon color="error" />
    }
    if (health.draining || !health.enabled || health.failureCount > 0) {
      return <WarningIcon color="warning" />
    }
    return <HealthyIcon color="success" />
  }

  const getHealthColor = (health: GridInstanceHealthStatus): 'success' | 'warning' | 'error' => {
    if (health.unhealthy || !health.healthy) {
      return 'error'
    }
    if (health.draining || !health.enabled || health.failureCount > 0) {
      return 'warning'
    }
    return 'success'
  }

  const getHealthSummary = (health: GridInstanceHealthStatus): string => {
    if (health.unhealthy) return 'Unhealthy'
    if (!health.healthy) return 'Not Healthy'
    if (health.draining) return 'Draining'
    if (!health.enabled) return 'Disabled'
    if (health.failureCount > 0) return `Warning (${health.failureCount} failures)`
    return 'Healthy'
  }

  const getAvailabilityStatus = (health: GridInstanceHealthStatus): string => {
    if (health.availableForNewSessions) return 'Available'
    if (health.healthy && health.enabled) return 'At Capacity'
    return 'Unavailable'
  }

  const formatTimeSince = (timestamp: string): string => {
    if (!timestamp) return 'Never'
    const now = new Date()
    const then = new Date(timestamp)
    const secondsAgo = Math.floor((now.getTime() - then.getTime()) / 1000)
    
    if (secondsAgo < 60) return `${secondsAgo}s ago`
    if (secondsAgo < 3600) return `${Math.floor(secondsAgo / 60)}m ago`
    return `${Math.floor(secondsAgo / 3600)}h ago`
  }

  const getHealthStatusForInstance = (instanceId: string): GridInstanceHealthStatus | null => {
    return gridInstancesHealth.find(health => health.id === instanceId) || null
  }

  const getHealthPriority = (health: GridInstanceHealthStatus | null): number => {
    if (!health) return 3 // No health data - medium priority
    if (health.unhealthy || !health.healthy) return 0 // Unhealthy - highest priority
    if (health.draining || !health.enabled || health.failureCount > 0) return 1 // Warning - high priority
    return 2 // Healthy - lowest priority
  }

  const getSortedGridInstances = (): GridInstance[] => {
    return [...gridInstances].sort((a, b) => {
      const healthA = getHealthStatusForInstance(a.id)
      const healthB = getHealthStatusForInstance(b.id)
      const priorityA = getHealthPriority(healthA)
      const priorityB = getHealthPriority(healthB)
      
      // Sort by priority first (0 = highest priority)
      if (priorityA !== priorityB) {
        return priorityA - priorityB
      }
      
      // If same priority, sort by ID alphabetically
      return a.id.localeCompare(b.id)
    })
  }

  if (loading) {
    return (
      <Box display="flex" justifyContent="center" alignItems="center" minHeight="400px">
        <CircularProgress />
      </Box>
    )
  }

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        Configuration
      </Typography>
      
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}
      
      {success && (
        <Alert severity="success" sx={{ mb: 2 }}>
          {success}
        </Alert>
      )}
      
      <Grid container spacing={2}>
        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 2, height: 'fit-content' }}>
            <Box display="flex" justifyContent="space-between" alignItems="center" mb={1}>
              <Typography variant="h6">
                Routing Rules (YAML)
              </Typography>
              <Box display="flex" alignItems="center">
                <Checkbox
                  checked={routingRulesEnabled}
                  onChange={(e) => setRoutingRulesEnabled(e.target.checked)}
                />
                <Typography variant="body2">
                  Enabled
                </Typography>
              </Box>
            </Box>
            
            <TextField
              multiline
              fullWidth
              rows={15}
              value={yamlContent}
              onChange={(e) => setYamlContent(e.target.value)}
              variant="outlined"
              sx={{
                mb: 2,
                '& .MuiInputBase-input': {
                  fontFamily: 'monospace',
                  fontSize: '14px'
                }
              }}
              placeholder="# Enter YAML routing rules configuration here"
            />
            
            <Box display="flex" gap={2}>
              <Button
                variant="contained"
                color="primary"
                onClick={handleUpdate}
                disabled={!hasChanges || saving}
              >
                {saving ? <CircularProgress size={20} /> : 'Update'}
              </Button>
              
              <Button
                variant="outlined"
                onClick={handleDiscard}
                disabled={!hasChanges || saving}
              >
                Discard
              </Button>
            </Box>
          </Paper>
        </Grid>
        
        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 2 }}>
            <Box display="flex" justifyContent="space-between" alignItems="center" mb={2}>
              <Typography variant="h6">
                Grid Instances Health Status
              </Typography>
              <Box display="flex" gap={1}>
                <IconButton
                  size="small"
                  onClick={fetchGridInstancesHealth}
                  disabled={healthLoading}
                  title="Refresh Health Status"
                >
                  <RefreshIcon />
                </IconButton>
                <Button
                  variant="contained"
                  startIcon={<AddIcon />}
                  onClick={() => setShowAddDialog(true)}
                >
                  Add Instance
                </Button>
              </Box>
            </Box>
            
            {healthLoading && (
              <Box mb={2}>
                <LinearProgress />
                <Typography variant="body2" color="textSecondary" sx={{ mt: 1 }}>
                  Refreshing health status...
                </Typography>
              </Box>
            )}

            {hasGridChanges && (
              <Box display="flex" gap={2} mb={2} p={2} bgcolor="grey.50" borderRadius={1}>
                <Button
                  variant="contained"
                  color="primary"
                  onClick={() => setShowUpdateDialog(true)}
                  disabled={updating}
                >
                  {updating ? <CircularProgress size={20} /> : 'Update'}
                </Button>
                
                <Button
                  variant="outlined"
                  onClick={handleDiscardGridChanges}
                  disabled={updating}
                >
                  Discard
                </Button>
                
                <Typography variant="body2" color="textSecondary" sx={{ alignSelf: 'center', ml: 1 }}>
                  You have unsaved changes to Grid instances
                </Typography>
              </Box>
            )}

            {gridInstances.length === 0 ? (
              <Typography variant="body2" color="textSecondary" sx={{ textAlign: 'center', py: 4 }}>
                No Grid instances configured. Click "Add Instance" to get started.
              </Typography>
            ) : (
              <Box 
                sx={{ 
                  maxHeight: 'calc(100vh - 200px)', 
                  minHeight: 'calc(100vh - 200px)',
                  overflowY: 'auto',
                  pr: 1,
                  '&::-webkit-scrollbar': {
                    width: '8px',
                  },
                  '&::-webkit-scrollbar-track': {
                    background: '#f1f1f1',
                    borderRadius: '4px',
                  },
                  '&::-webkit-scrollbar-thumb': {
                    background: '#c1c1c1',
                    borderRadius: '4px',
                  },
                  '&::-webkit-scrollbar-thumb:hover': {
                    background: '#a8a8a8',
                  },
                }}
              >
                {getSortedGridInstances().map((instance) => {
                  const health = getHealthStatusForInstance(instance.id)
                  return (
                    <Card key={instance.id} sx={{ mb: 2, border: '1px solid #e0e0e0' }}>
                      <CardContent sx={{ p: 2, '&:last-child': { pb: 2 } }}>
                        <Box display="flex" justifyContent="space-between" alignItems="flex-start" mb={2}>
                          <Box display="flex" alignItems="center" gap={1}>
                            {health ? getHealthIcon(health) : <WarningIcon color="disabled" />}
                            <Typography variant="h6" component="div">
                              {instance.id}
                            </Typography>
                            {health && (
                              <Chip
                                label={getHealthSummary(health)}
                                color={getHealthColor(health)}
                                size="small"
                              />
                            )}
                          </Box>
                          <Box display="flex" gap={1}>
                            <Checkbox
                              checked={instance.enabled}
                              onChange={() => handleToggleInstance(instance)}
                              title="Enable/Disable Instance"
                            />
                            <IconButton
                              size="small"
                              onClick={() => handleDeleteClick(instance)}
                              title="Delete Instance"
                            >
                              <DeleteIcon fontSize="small" />
                            </IconButton>
                          </Box>
                        </Box>

                        <Typography variant="body2" color="textSecondary" sx={{ mb: 2 }}>
                          <strong>URL:</strong> {instance.url}
                        </Typography>

                        {health ? (
                          <Grid container spacing={2}>
                            <Grid item xs={6} sm={3}>
                              <Typography variant="body2" color="textSecondary">
                                <strong>Status:</strong>
                              </Typography>
                              <Chip
                                label={health.status}
                                color={getHealthColor(health)}
                                size="small"
                                variant="outlined"
                              />
                            </Grid>
                            
                            <Grid item xs={6} sm={3}>
                              <Typography variant="body2" color="textSecondary">
                                <strong>Availability:</strong>
                              </Typography>
                              <Typography variant="body2">
                                {getAvailabilityStatus(health)}
                              </Typography>
                            </Grid>
                            
                            <Grid item xs={6} sm={3}>
                              <Typography variant="body2" color="textSecondary">
                                <strong>Sessions:</strong>
                              </Typography>
                              <Typography variant="body2">
                                {health.sessionCount}
                              </Typography>
                            </Grid>
                            
                            <Grid item xs={6} sm={3}>
                              <Typography variant="body2" color="textSecondary">
                                <strong>Load:</strong>
                              </Typography>
                              <Box display="flex" alignItems="center" gap={1}>
                                <LinearProgress
                                  variant="determinate"
                                  value={Math.min(health.loadFactor, 100)}
                                  sx={{ 
                                    width: 60, 
                                    height: 6,
                                    backgroundColor: '#f0f0f0',
                                    '& .MuiLinearProgress-bar': {
                                      backgroundColor: health.loadFactor > 80 ? '#f44336' : 
                                                     health.loadFactor > 60 ? '#ff9800' : '#4caf50'
                                    }
                                  }}
                                />
                                <Typography variant="body2">
                                  {Math.round(health.loadFactor)}%
                                </Typography>
                              </Box>
                            </Grid>
                            
                            <Grid item xs={6} sm={3}>
                              <Typography variant="body2" color="textSecondary">
                                <strong>Failures:</strong>
                              </Typography>
                              <Typography variant="body2" color={health.failureCount > 0 ? 'error' : 'textPrimary'}>
                                {health.failureCount}
                              </Typography>
                            </Grid>
                            
                            <Grid item xs={6} sm={3}>
                              <Typography variant="body2" color="textSecondary">
                                <strong>Last Health Check:</strong>
                              </Typography>
                              <Tooltip title={health.lastHealthCheck}>
                                <Typography variant="body2">
                                  {formatTimeSince(health.lastHealthCheck)}
                                </Typography>
                              </Tooltip>
                            </Grid>
                            
                            <Grid item xs={6} sm={3}>
                              <Typography variant="body2" color="textSecondary">
                                <strong>Enabled:</strong>
                              </Typography>
                              <Typography variant="body2">
                                {health.enabled ? 'Yes' : 'No'}
                              </Typography>
                            </Grid>
                            
                            <Grid item xs={6} sm={3}>
                              <Typography variant="body2" color="textSecondary">
                                <strong>Draining:</strong>
                              </Typography>
                              <Typography variant="body2" color={health.draining ? 'warning.main' : 'textPrimary'}>
                                {health.draining ? 'Yes' : 'No'}
                              </Typography>
                            </Grid>
                          </Grid>
                        ) : (
                          <Alert severity="warning" sx={{ mt: 1 }}>
                            Health status not available. The Gateway may not be running or this instance may not be registered.
                          </Alert>
                        )}
                      </CardContent>
                    </Card>
                  )
                })}
              </Box>
            )}
          </Paper>
        </Grid>
      </Grid>
      
      <Dialog open={showAddDialog} onClose={() => setShowAddDialog(false)}>
        <DialogTitle>Add Grid Instance</DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            margin="dense"
            label="Grid URL"
            fullWidth
            variant="outlined"
            value={newInstanceUrl}
            onChange={(e) => setNewInstanceUrl(e.target.value)}
            placeholder="http://localhost:4444"
            sx={{ mb: 2 }}
          />
          <TextField
            margin="dense"
            label="Instance ID (optional)"
            fullWidth
            variant="outlined"
            value={newInstanceId}
            onChange={(e) => setNewInstanceId(e.target.value)}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setShowAddDialog(false)}>Cancel</Button>
          <Button onClick={handleAddInstance} variant="contained">Add</Button>
        </DialogActions>
      </Dialog>
      
      <Dialog open={showUpdateDialog} onClose={() => setShowUpdateDialog(false)}>
        <DialogTitle>Confirm Grid Instance Updates</DialogTitle>
        <DialogContent>
          <Typography>
            Are you sure you want to update the grid instance configurations?
            This will apply all pending changes.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setShowUpdateDialog(false)}>Cancel</Button>
          <Button onClick={handleUpdateInstances} variant="contained" color="primary">
            Confirm Update
          </Button>
        </DialogActions>
      </Dialog>
      
      <Dialog open={showDeleteDialog} onClose={() => setShowDeleteDialog(false)}>
        <DialogTitle>Confirm Delete</DialogTitle>
        <DialogContent>
          <Typography>
            Are you sure you want to delete grid instance "{instanceToDelete?.id}"?
            This action cannot be undone.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setShowDeleteDialog(false)}>Cancel</Button>
          <Button onClick={handleConfirmDelete} variant="contained" color="error">
            Delete
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

export default Configuration