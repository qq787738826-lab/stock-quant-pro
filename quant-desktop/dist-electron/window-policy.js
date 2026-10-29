const ACTIONS = {
    'stock-quant-desktop:retry': 'retry',
    'stock-quant-desktop:exit': 'exit',
    'stock-quant-desktop:retry-stop': 'retry-stop',
    'stock-quant-desktop:return': 'return',
};
export function classifyNavigation(url) {
    const action = ACTIONS[url];
    if (action) {
        return { kind: 'action', action };
    }
    try {
        const parsed = new URL(url);
        if (parsed.origin === 'http://127.0.0.1:8080') {
            return { kind: 'internal' };
        }
        if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
            return { kind: 'external' };
        }
    }
    catch {
        return { kind: 'blocked' };
    }
    return { kind: 'blocked' };
}
export function activateExistingWindow(window) {
    if (!window || window.isDestroyed()) {
        return;
    }
    if (window.isMinimized()) {
        window.restore();
    }
    window.show();
    window.focus();
}
