import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Alert } from 'antd';
import { ApiProblem } from '@/shared/api';

interface Props {
  children: ReactNode;
  compact?: boolean;
  fallback?: ReactNode;
}

interface State {
  error: Error | null;
}

/** Catches render errors of a page or widget so the shell keeps working. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    if (import.meta.env.DEV) console.error(error, info.componentStack);
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    if (this.props.fallback !== undefined) return this.props.fallback;
    if (this.props.compact) return null;
    const detail = error instanceof ApiProblem ? (error.problem.detail ?? error.code) : error.message;
    return <Alert type="error" showIcon message={detail} />;
  }
}
