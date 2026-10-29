export type DesktopAction = 'retry' | 'exit' | 'retry-stop' | 'return';

export type NavigationDecision =
  | {kind: 'internal'}
  | {kind: 'external'}
  | {kind: 'action'; action: DesktopAction}
  | {kind: 'blocked'};

const ACTIONS: Readonly<Record<string, DesktopAction>> = {
  'stock-quant-desktop:retry': 'retry',
  'stock-quant-desktop:exit': 'exit',
  'stock-quant-desktop:retry-stop': 'retry-stop',
  'stock-quant-desktop:return': 'return',
};

export function classifyNavigation(url: string): NavigationDecision {
  const action = ACTIONS[url];
  if (action) {
    return {kind: 'action', action};
  }
  try {
    const parsed = new URL(url);
    if (parsed.origin === 'http://127.0.0.1:8080') {
      return {kind: 'internal'};
    }
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
      return {kind: 'external'};
    }
  } catch {
    return {kind: 'blocked'};
  }
  return {kind: 'blocked'};
}

export interface ActivatableWindow {
  isDestroyed(): boolean;
  isMinimized(): boolean;
  restore(): void;
  show(): void;
  focus(): void;
}

export function activateExistingWindow(window: ActivatableWindow | null): void {
  if (!window || window.isDestroyed()) {
    return;
  }
  if (window.isMinimized()) {
    window.restore();
  }
  window.show();
  window.focus();
}
