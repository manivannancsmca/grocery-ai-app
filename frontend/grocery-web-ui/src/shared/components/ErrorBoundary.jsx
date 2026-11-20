import { Component } from 'react'
import { ErrorState } from './ui'

/** @typedef {import('react').ReactNode} ReactNode */

/**
 * @typedef {object} ErrorBoundaryProps
 * @property {ReactNode} children
 * @property {ReactNode} [fallback]
 */

/**
 * @typedef {object} ErrorBoundaryState
 * @property {Error | null} error
 */

/** Last-resort guard so a rendering bug shows a recoverable screen instead of a blank page. */
export class ErrorBoundary extends Component {
  state = { error: null }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidCatch(error, info) {
    console.error('Unhandled UI error', error, info.componentStack)
  }

  handleReload = () => {
    window.location.reload()
  }

  render() {
    if (!this.state.error) return this.props.children
    if (this.props.fallback) return this.props.fallback

    return (
      <div className="mx-auto max-w-lg p-6">
        <ErrorState
          title="Something went wrong"
          message="The page hit an unexpected error. Reloading usually fixes it."
          onRetry={this.handleReload}
          retryLabel="Reload page"
        />
      </div>
    )
  }
}
