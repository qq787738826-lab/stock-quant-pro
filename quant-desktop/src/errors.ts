const SAFE_CODE = /^[A-Z][A-Z0-9_]{2,127}$/;

const SAFE_MESSAGES: Readonly<Record<string, string>> = {
  STOCK_QUANT_REPOSITORY_NOT_FOUND:
    '未找到与本安装包绑定的 Stock Quant Pro 仓库。请在原仓库重新构建安装包。',
  DESKTOP_RUNTIME_BINDING_MISSING:
    '安装包缺少本机仓库绑定信息，请重新构建并安装桌面客户端。',
  DESKTOP_RUNTIME_BINDING_INVALID:
    '安装包与当前仓库版本不一致，请重新构建并安装桌面客户端。',
  DESKTOP_FORMAL_ARTIFACT_MISSING:
    '正式 Backend 制品或 proof 缺失，请先完成受控 Production 构建。',
  DESKTOP_LAUNCHER_MISSING:
    '正式 Production 生命周期脚本缺失，请修复本机仓库。',
  DESKTOP_POWERSHELL_UNAVAILABLE:
    'Windows PowerShell 不可用，无法安全调用正式生命周期合同。',
  DESKTOP_LIFECYCLE_TIMEOUT:
    '正式生命周期操作超时，客户端没有继续重试。',
  DESKTOP_LIFECYCLE_CONTRACT_INVALID:
    '正式生命周期返回了无效的脱敏状态，操作已安全终止。',
  DESKTOP_BACKEND_HEALTH_TIMEOUT:
    'Backend 未在限定时间内达到 HEALTHY。',
  DESKTOP_BACKEND_BLOCKED:
    'Backend 健康检查被安全门禁阻止。',
  DESKTOP_BACKEND_VERSION_MISMATCH:
    'Backend 版本与桌面客户端绑定版本不一致。',
  DESKTOP_BACKEND_STOP_UNCONFIRMED:
    '无法通过正式状态合同确认 Backend 已停止。',
  DESKTOP_BACKEND_PORT_STILL_OPEN:
    'Backend 已请求停止，但 127.0.0.1:8080 仍在监听。',
  M6_DATABASE_NOT_LISTENING:
    '本地研究数据库未在 127.0.0.1:38432 运行。',
  M6_FORMAL_ARTIFACT_MISSING:
    '正式 Backend 制品缺失，请先完成受控 Production 构建。',
  M6_JAVA_17_REQUIRED: '未找到可用的 Java 17。',
  M6_HOST_BROKER_OPERATION_FAILED:
    'Resident Broker 未能完成正式生命周期请求。',
  M6_SYSTEM_HEALTH_BLOCKED: 'Backend 未达到安全的 HEALTHY 状态。',
  HOST_BROKER_TASK_NOT_INSTALLED:
    'Resident Broker 尚未安装，请先修复 StockQuantLocalBroker。',
  HOST_BROKER_TASK_DISABLED:
    'Resident Broker watchdog 已禁用，请先修复 StockQuantLocalBroker。',
  HOST_BROKER_TASK_DEFINITION_INVALID:
    'Resident Broker watchdog 配置无效，请先修复 StockQuantLocalBroker。',
  HOST_BROKER_TASK_STATUS_UNAVAILABLE:
    'Windows 无法检查 Resident Broker 状态。',
  HOST_BROKER_WATCHDOG_RECOVERY_TIMEOUT:
    'Resident Broker 未能在限定时间内恢复。',
  STOCK_QUANT_HOST_BROKER_RESULT_TIMEOUT:
    '等待 Resident Broker 脱敏结果超时。',
  STOCK_QUANT_HOST_BROKER_GIT_BASELINE_INVALID:
    '当前仓库不是已推送且干净的正式任务基线。',
};

export class DesktopLifecycleError extends Error {
  readonly code: string;

  constructor(code: string) {
    const safeCode = SAFE_CODE.test(code)
      ? code
      : 'DESKTOP_UNEXPECTED_FAILURE';
    super(safeCode);
    this.name = 'DesktopLifecycleError';
    this.code = safeCode;
  }
}

export interface SafeFailure {
  code: string;
  message: string;
}

export function safeFailure(error: unknown): SafeFailure {
  const code = error instanceof DesktopLifecycleError
    ? error.code
    : 'DESKTOP_UNEXPECTED_FAILURE';
  return {
    code,
    message: SAFE_MESSAGES[code]
      ?? `正式生命周期操作未完成（${code}）。`,
  };
}

export function isSafeCode(value: string | undefined): value is string {
  return value !== undefined && SAFE_CODE.test(value);
}
