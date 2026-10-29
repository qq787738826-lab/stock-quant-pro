const STARTUP_STEPS = [
    { stage: 'checking_service', label: '正在检查服务' },
    { stage: 'starting_backend', label: '正在启动后端' },
    { stage: 'connecting_database', label: '正在连接数据库' },
    { stage: 'loading_client', label: '正在加载客户端' },
];
const SHUTDOWN_STEPS = [
    { stage: 'stopping_backend', label: '正在停止后端' },
    { stage: 'verifying_stopped', label: '正在确认服务已停止' },
    { stage: 'closing_port', label: '正在关闭本地端口' },
];
function escapeHtml(value) {
    return value
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}
function progress(steps, current) {
    const activeIndex = Math.max(0, steps.findIndex(step => step.stage === current));
    return steps.map((step, index) => {
        const state = index < activeIndex
            ? 'done'
            : index === activeIndex
                ? 'active'
                : 'pending';
        const marker = state === 'done' ? '✓' : String(index + 1);
        return `<li class="${state}"><i>${marker}</i><span>${step.label}</span></li>`;
    }).join('');
}
function content(view) {
    if (view.kind === 'startup') {
        return `
      <h1>Stock Quant Pro</h1>
      <p class="lead">正在启动研究环境...</p>
      <ol class="progress">${progress(STARTUP_STEPS, view.stage)}</ol>`;
    }
    if (view.kind === 'shutdown') {
        return `
      <h1>Stock Quant Pro</h1>
      <p class="lead">正在安全关闭研究环境...</p>
      <ol class="progress compact">${progress(SHUTDOWN_STEPS, view.stage)}</ol>`;
    }
    const startup = view.phase === 'startup';
    const secondary = view.canReturnToClient
        ? '<a class="button secondary" href="stock-quant-desktop:return">返回客户端</a>'
        : '<a class="button secondary" href="stock-quant-desktop:exit">退出</a>';
    return `
    <h1>${startup ? 'Stock Quant Pro 启动失败' : 'Stock Quant Pro 关闭失败'}</h1>
    <p class="lead error">${escapeHtml(view.failure.message)}</p>
    <p class="code">${escapeHtml(view.failure.code)}</p>
    <div class="actions">
      <a class="button" href="stock-quant-desktop:${startup ? 'retry' : 'retry-stop'}">重新尝试</a>
      ${secondary}
    </div>`;
}
export function renderDesktopView(view) {
    return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta http-equiv="Content-Security-Policy"
        content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Stock Quant Pro</title>
  <style>
    :root { color-scheme: dark; font-family: "Segoe UI", "Microsoft YaHei UI", sans-serif; }
    * { box-sizing: border-box; }
    body { margin: 0; min-height: 100vh; display: grid; place-items: center; color: #e8f0fb;
      background: radial-gradient(circle at 50% 15%, #142a46 0, #091521 42%, #060d15 100%); }
    .shell { width: min(560px, calc(100vw - 48px)); padding: 48px; border: 1px solid #203b59;
      border-radius: 20px; background: rgba(8, 20, 33, .92); box-shadow: 0 24px 80px #0009; }
    .brand { display: flex; align-items: center; gap: 14px; margin-bottom: 34px; }
    .logo { width: 52px; height: 52px; display: grid; place-items: center; border-radius: 13px;
      color: #06111c; background: linear-gradient(135deg, #39d8c6, #56a8ff); font-weight: 800;
      letter-spacing: -2px; box-shadow: 0 8px 28px #26bfc844; }
    .brand span { color: #7f95ad; font-size: 13px; letter-spacing: .12em; text-transform: uppercase; }
    h1 { margin: 0; font-size: 30px; font-weight: 650; letter-spacing: -.02em; }
    .lead { margin: 12px 0 30px; color: #9cb0c5; font-size: 17px; line-height: 1.6; }
    .lead.error { color: #d9e5f2; margin-bottom: 10px; }
    .code { margin: 0 0 30px; color: #7890a9; font: 12px/1.5 Consolas, monospace; }
    .progress { list-style: none; padding: 0; margin: 0; display: grid; gap: 17px; }
    .progress li { display: flex; align-items: center; gap: 14px; color: #61758a; }
    .progress i { width: 29px; height: 29px; display: grid; place-items: center; border-radius: 50%;
      border: 1px solid #28435f; font-style: normal; font-size: 12px; }
    .progress li.active { color: #eaf4ff; }
    .progress li.active i { color: #07141d; border-color: #3cd6c4; background: #3cd6c4;
      box-shadow: 0 0 0 6px #3cd6c416; }
    .progress li.done { color: #8ea4ba; }
    .progress li.done i { color: #56d9c8; border-color: #328d8b; }
    .progress.compact { gap: 20px; }
    .actions { display: flex; gap: 12px; margin-top: 8px; }
    .button { min-width: 116px; padding: 11px 18px; border-radius: 9px; text-align: center;
      text-decoration: none; color: #06131c; background: #43d4c3; font-weight: 650; }
    .button:hover { background: #64e2d3; }
    .button.secondary { color: #d9e7f4; background: #182d42; border: 1px solid #2b4965; }
    .button.secondary:hover { background: #213a52; }
  </style>
</head>
<body>
  <main class="shell">
    <div class="brand"><div class="logo">SQ</div><span>Research Desktop</span></div>
    ${content(view)}
  </main>
</body>
</html>`;
}
export function desktopViewDataUrl(view) {
    return `data:text/html;charset=utf-8,${encodeURIComponent(renderDesktopView(view))}`;
}
