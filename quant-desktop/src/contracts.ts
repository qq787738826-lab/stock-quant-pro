export type LifecycleStage =
  | 'checking_service'
  | 'starting_backend'
  | 'connecting_database'
  | 'loading_client'
  | 'stopping_backend'
  | 'verifying_stopped'
  | 'closing_port';

export type AutoRecoveryState = 'ARMED' | 'DISARMED' | 'EXHAUSTED';

export interface ProductionStatus {
  backendState: 'RUNNING' | 'STOPPED';
  fastApiState: 'RUNNING' | 'STOPPED';
  javaState: 'RUNNING' | 'STOPPED';
  backendPortOpen: boolean;
  fastApiPortOpen: boolean;
  autoRecovery: AutoRecoveryState;
}

export interface HealthSnapshot {
  status: 'HEALTHY' | 'DEGRADED' | 'BLOCKED';
  gitCommit: string;
  fastApiStatus: 'HEALTHY' | 'BLOCKED';
  fastApiReason: string;
}

export interface ProductionPortStatus {
  backendPortOpen: boolean;
  fastApiPortOpen: boolean;
}

export interface ProductionGateway {
  status(): Promise<ProductionStatus>;
  start(): Promise<ProductionStatus>;
  stop(): Promise<ProductionStatus>;
  health(): Promise<HealthSnapshot>;
  ports(): Promise<ProductionPortStatus>;
  wait(milliseconds: number): Promise<void>;
  now(): number;
}

export type StageListener = (stage: LifecycleStage) => void;
