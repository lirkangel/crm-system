import { Component, type ReactNode } from "react";
import i18n from "@/i18n";

/**
 * Shows the failed view instead of a blank page if rendering throws. Retry re-renders the children; it does not
 * reload the page, because the link token is held in memory only and the fragment is already gone from the URL.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="space-y-3">
        <p role="alert">{i18n.t("common.error")}</p>
        <button
          type="button"
          className="min-h-11 rounded-lg border bg-white px-4"
          onClick={() => this.setState({ failed: false })}
        >
          {i18n.t("common.retry")}
        </button>
      </div>
    );
  }
}
