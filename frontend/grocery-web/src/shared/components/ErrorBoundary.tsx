import { Component, type ErrorInfo, type ReactNode } from 'react'
import { ErrorState } from './ui'

interface ErrorBoundaryProps {
  children: ReactNode
  fallback?: ReactNode
}

interface ErrorBoundaryState {
  error: Error | null
}

/** Last-resort guard so a rendering bug shows a recoverable screen instead of a blank page. */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Unhandled UI error', error, info.componentStack)
  }

  private readonly handleReload = (): void => {
    window.location.reload()
  }

  render(): ReactNode {
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
