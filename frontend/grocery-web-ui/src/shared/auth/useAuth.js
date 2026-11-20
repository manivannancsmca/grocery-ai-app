import { useContext } from 'react'
import { AuthContext } from './authContext'

/** @typedef {import('./authContext').AuthContextValue} AuthContextValue */

/** @returns {AuthContextValue} */
export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return context
}
