import { Component, type ErrorInfo, type ReactNode } from "react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";

function RouteErrorFallback({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation();

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-3 p-8 text-center">
      <h1 className="text-xl font-semibold tracking-tight">{t("errors.boundary.title")}</h1>
      <p className="text-muted-foreground">{t("errors.boundary.message")}</p>
      <Button variant="outline" onClick={onRetry}>
        {t("errors.boundary.retry")}
      </Button>
    </div>
  );
}

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
}

/** Contains a thrown render error to its route, leaving the persistent shell intact. */
export class RouteErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("Route render error:", error, info.componentStack);
  }

  private reset = () => this.setState({ hasError: false });

  render() {
    if (this.state.hasError) {
      return <RouteErrorFallback onRetry={this.reset} />;
    }
    return this.props.children;
  }
}
