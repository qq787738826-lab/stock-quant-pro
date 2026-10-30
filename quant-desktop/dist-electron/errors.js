const SAFE_CODE = /^[A-Z][A-Z0-9_]{2,127}$/;
const SAFE_MESSAGES = {
    STOCK_QUANT_REPOSITORY_NOT_FOUND: '未找到与本安装包绑定的 Stock Quant Pro 仓库。请在原仓库重新构建安装包。',
    DESKTOP_RUNTIME_BINDING_MISSING: '安装包缺少本机仓库绑定信息，请重新构建并安装桌面客户端。',
    DESKTOP_RUNTIME_BINDING_INVALID: '安装包与当前仓库版本不一致，请重新构建并安装桌面客户端。',
    DESKTOP_FORMAL_ARTIFACT_MISSING: '正式 Backend 制品或 proof 缺失，请先完成受控 Production 构建。',
    DESKTOP_LAUNCHER_MISSING: '正式 Production 生命周期脚本缺失，请修复本机仓库。',
    DESKTOP_POWERSHELL_UNAVAILABLE: 'Windows PowerShell 不可用，无法安全调用正式生命周期合同。',
    DESKTOP_LIFECYCLE_TIMEOUT: '正式生命周期操作超时，客户端没有继续重试。',
    DESKTOP_LIFECYCLE_CONTRACT_INVALID: '正式生命周期返回了无效的脱敏状态，操作已安全终止。',
    DESKTOP_BACKEND_HEALTH_TIMEOUT: 'Backend 未在限定时间内达到 HEALTHY。',
    DESKTOP_BACKEND_BLOCKED: 'Backend 健康检查被安全门禁阻止。',
    DESKTOP_FASTAPI_HEALTH_BLOCKED: 'FastAPI 8001 健康检查失败，客户端不会加载业务页面。',
    FASTAPI_HEALTH_HTTP_STATUS: 'FastAPI 8001 返回了异常 HTTP 状态，客户端不会加载业务页面。',
    FASTAPI_HEALTH_PAYLOAD_INVALID: 'FastAPI 8001 健康响应格式或版本无效。',
    FASTAPI_HEALTH_INTERRUPTED: 'FastAPI 8001 健康检查被中断。',
    FASTAPI_HEALTH_UNAVAILABLE: 'FastAPI 8001 当前不可用。',
    DESKTOP_BACKEND_VERSION_MISMATCH: 'Backend 版本与桌面客户端绑定版本不一致。',
    DESKTOP_BACKEND_STOP_UNCONFIRMED: '无法通过正式状态合同确认 Backend 已停止。',
    DESKTOP_BACKEND_PORT_STILL_OPEN: 'Backend 已请求停止，但 127.0.0.1:8080 仍在监听。',
    DESKTOP_FASTAPI_PORT_STILL_OPEN: 'FastAPI 已请求停止，但 127.0.0.1:8001 仍在监听。',
    M6_DATABASE_NOT_LISTENING: '本地研究数据库未在 127.0.0.1:38432 运行。',
    M6_FORMAL_ARTIFACT_MISSING: '正式 Backend 制品缺失，请先完成受控 Production 构建。',
    M6_JAVA_17_REQUIRED: '未找到可用的 Java 17。',
    M6_JAVA_17_RUNTIME_INVALID: 'Java 8080 无法通过固定 Java 17 运行时校验。',
    M6_FASTAPI_PYTHON_MISSING: 'FastAPI 8001 无法启动：固定的 quant-ai\\.venv\\Scripts\\python.exe 不存在。',
    M6_FASTAPI_PYTHON_INVALID: 'FastAPI 8001 无法启动：固定 Python 运行时不是可用的 Python 3.11。',
    M6_FASTAPI_PORT_OCCUPIED: '127.0.0.1:8001 被未知进程占用，Resident Broker 已拒绝接管。',
    M6_FASTAPI_PROCESS_STATE_INVALID: 'FastAPI 8001 的 PID 状态文件无效，Resident Broker 已安全停止操作。',
    M6_FASTAPI_PROCESS_IDENTITY_INVALID: 'FastAPI 8001 的 PID、命令行、工作目录或端口归属校验失败。',
    M6_FASTAPI_EXITED: 'FastAPI 8001 在达到健康状态前异常退出。',
    M6_FASTAPI_START_FAILED: 'Resident Broker 无法启动 FastAPI 8001。',
    M6_FASTAPI_HEALTH_TIMEOUT: 'FastAPI 8001 未在限定时间内达到 HEALTHY。',
    M6_FASTAPI_STOP_FAILED: 'FastAPI 8001 未能安全停止或端口 8001 未释放。',
    M6_BACKEND_PORT_OCCUPIED: '127.0.0.1:8080 被未知进程占用，Resident Broker 已拒绝接管。',
    M6_PRODUCTION_PROCESS_STATE_INVALID: 'Java 8080 的 PID 状态文件无效，Resident Broker 已安全停止操作。',
    M6_PRODUCTION_PROCESS_IDENTITY_INVALID: 'Java 8080 的 PID、命令行、工作目录或端口归属校验失败。',
    M6_PRODUCTION_BACKEND_EXITED: 'Java 8080 在达到复合健康状态前异常退出。',
    M6_PRODUCTION_BACKEND_START_FAILED: 'Java 8080 启动失败，FastAPI 8001 已按合同回滚。',
    M6_PRODUCTION_HEALTH_TIMEOUT: 'Java 8080 未在限定时间内达到复合 HEALTHY。',
    M6_PRODUCTION_STOP_FAILED: 'Java 8080 未能安全停止或端口 8080 未释放。',
    M6_PRODUCTION_RUNNING_VERSION_MISMATCH: '正在运行的本地服务与当前 Git/制品绑定不一致。',
    M6_PRODUCTION_PORT_IDENTITY_INVALID: '本地服务端口存在多个监听归属，Resident Broker 已拒绝接管。',
    M6_PRODUCTION_AUTOSTART_BINDING_INVALID: '本地服务的 Git 分支、提交或正式制品绑定无效。',
    M6_PRODUCTION_AUTOSTART_STATE_INVALID: 'Resident Broker 的 autoRecovery 状态文件无效。',
    M6_PRODUCTION_AUTOSTART_WRITE_FAILED: 'Resident Broker 无法持久化 autoRecovery 状态。',
    M6_PRODUCTION_AUTOSTART_DISARM_FAILED: 'Resident Broker 无法解除 autoRecovery，关闭已安全中止。',
    M6_PRODUCTION_AUTOSTART_ARM_FAILED: '本地服务已回滚：Resident Broker 无法启用 autoRecovery。',
    M6_HOST_BROKER_OPERATION_FAILED: 'Resident Broker 未能完成正式生命周期请求。',
    M6_SYSTEM_HEALTH_BLOCKED: 'Backend 未达到安全的 HEALTHY 状态。',
    HOST_BROKER_TASK_NOT_INSTALLED: 'Resident Broker 尚未安装，请先修复 StockQuantLocalBroker。',
    HOST_BROKER_TASK_DISABLED: 'Resident Broker watchdog 已禁用，请先修复 StockQuantLocalBroker。',
    HOST_BROKER_TASK_DEFINITION_INVALID: 'Resident Broker watchdog 配置无效，请先修复 StockQuantLocalBroker。',
    HOST_BROKER_TASK_STATUS_UNAVAILABLE: 'Windows 无法检查 Resident Broker 状态。',
    HOST_BROKER_WATCHDOG_RECOVERY_TIMEOUT: 'Resident Broker 未能在限定时间内恢复。',
    STOCK_QUANT_HOST_BROKER_RESULT_TIMEOUT: '等待 Resident Broker 脱敏结果超时。',
    STOCK_QUANT_HOST_BROKER_GIT_BASELINE_INVALID: '当前仓库不是已推送且干净的正式任务基线。',
};
export class DesktopLifecycleError extends Error {
    code;
    constructor(code) {
        const safeCode = SAFE_CODE.test(code)
            ? code
            : 'DESKTOP_UNEXPECTED_FAILURE';
        super(safeCode);
        this.name = 'DesktopLifecycleError';
        this.code = safeCode;
    }
}
export function safeFailure(error) {
    const code = error instanceof DesktopLifecycleError
        ? error.code
        : 'DESKTOP_UNEXPECTED_FAILURE';
    return {
        code,
        message: SAFE_MESSAGES[code]
            ?? `正式生命周期操作未完成（${code}）。`,
    };
}
export function isSafeCode(value) {
    return value !== undefined && SAFE_CODE.test(value);
}
