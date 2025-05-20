//
//

import React, { useState, useEffect } from 'react'
import {
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormGroup,
  IconButton,
  Typography
} from '@mui/material'
import { ViewColumn as ViewColumnIcon } from '@mui/icons-material'

interface ColumnSelectorProps {
  sessions: any[]
  selectedColumns: string[]
  onColumnSelectionChange: (columns: string[]) => void
}

const ColumnSelector: React.FC<ColumnSelectorProps> = ({
  sessions,
  selectedColumns,
  onColumnSelectionChange
}) => {
  const [open, setOpen] = useState(false)
  const [availableColumns, setAvailableColumns] = useState<string[]>([])
  const [localSelectedColumns, setLocalSelectedColumns] = useState<string[]>(selectedColumns)
  
  useEffect(() => {
    setLocalSelectedColumns(selectedColumns)
  }, [selectedColumns])

  useEffect(() => {
    const allKeys = new Set<string>()
    
    sessions.forEach(session => {
      try {
        const capabilities = JSON.parse(session.capabilities)
        Object.keys(capabilities).forEach(key => {
          if (
            typeof capabilities[key] !== 'object' && 
            !key.startsWith('goog:') && 
            !key.startsWith('moz:') &&
            key !== 'alwaysMatch' &&
            key !== 'firstMatch'
          ) {
            allKeys.add(key)
          }
        })
      } catch (e) {
        console.error('Error parsing capabilities:', e)
      }
    })
    
    setAvailableColumns(Array.from(allKeys).sort())
  }, [sessions])

  const handleToggle = (column: string) => {
    setLocalSelectedColumns(prev => {
      if (prev.includes(column)) {
        return prev.filter(col => col !== column)
      } else {
        return [...prev, column]
      }
    })
  }

  const handleClose = () => {
    setOpen(false)
  }

  const handleSave = () => {
    onColumnSelectionChange(localSelectedColumns)
    setOpen(false)
  }
  
  const handleSelectAll = (checked: boolean) => {
    if (checked) {
      setLocalSelectedColumns([...availableColumns])
    } else {
      setLocalSelectedColumns([])
    }
  }

  return (
    <Box>
      <IconButton
        aria-label="select columns"
        title="Select columns"
        onClick={() => setOpen(true)}
      >
        <ViewColumnIcon />
      </IconButton>
      
      <Dialog 
        open={open} 
        onClose={handleClose}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>
          Select Columns to Display
        </DialogTitle>
        <DialogContent dividers>
          <Typography variant="body2" gutterBottom>
            Select capability fields to display as additional columns:
          </Typography>
          <FormGroup>
            <FormControlLabel
              control={
                <Checkbox
                  checked={localSelectedColumns.length === availableColumns.length && availableColumns.length > 0}
                  indeterminate={localSelectedColumns.length > 0 && localSelectedColumns.length < availableColumns.length}
                  onChange={(e) => handleSelectAll(e.target.checked)}
                />
              }
              label={<Typography fontWeight="bold">Select All / Unselect All</Typography>}
            />
            {availableColumns.map(column => (
              <FormControlLabel
                key={column}
                control={
                  <Checkbox
                    checked={localSelectedColumns.includes(column)}
                    onChange={() => handleToggle(column)}
                  />
                }
                label={column}
              />
            ))}
          </FormGroup>
        </DialogContent>
        <DialogActions>
          <Button onClick={handleClose}>Cancel</Button>
          <Button onClick={handleSave} variant="contained" color="primary">
            Apply
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

export default ColumnSelector
