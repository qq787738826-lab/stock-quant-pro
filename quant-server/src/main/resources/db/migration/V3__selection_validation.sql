alter table market_scan_tasks
  add column if not exists scan_type varchar(20) not null default 'TEST',
  add column if not exists official boolean not null default false,
  add column if not exists source_task_id bigint,
  add column if not exists trade_date date,
  add column if not exists duration_ms bigint,
  add column if not exists candidate_threshold numeric(8,2) not null default 60;

update market_scan_tasks
set scan_type = case when requested_limit = 0 then 'FULL' else 'TEST' end
where scan_type is null or scan_type = 'TEST';

update market_scan_tasks
set official = true
where status = 'COMPLETED' and requested_limit = 0;

create index if not exists idx_market_scan_tasks_official
  on market_scan_tasks(official, status, id desc);

alter table market_scan_results
  add column if not exists eligible boolean not null default false,
  add column if not exists filter_reasons jsonb not null default '[]'::jsonb,
  add column if not exists avg_amount_20 numeric(24,4),
  add column if not exists return_5_pct numeric(12,4),
  add column if not exists return_20_pct numeric(12,4),
  add column if not exists rsi14 numeric(12,4),
  add column if not exists atr14_pct numeric(12,4),
  add column if not exists volume_ratio20 numeric(12,4),
  add column if not exists breakout20 boolean not null default false;

create index if not exists idx_market_scan_results_eligible_rank
  on market_scan_results(task_id, eligible desc, rank_no asc);

create table if not exists market_scan_failures (
  id bigserial primary key,
  task_id bigint not null references market_scan_tasks(id) on delete cascade,
  symbol varchar(12) not null,
  name varchar(80),
  error_message text not null,
  retry_count int not null default 0,
  resolved boolean not null default false,
  created_at timestamp not null default now(),
  updated_at timestamp not null default now(),
  unique(task_id, symbol)
);

create index if not exists idx_market_scan_failures_task
  on market_scan_failures(task_id, resolved, symbol);

create table if not exists market_data_update_tasks (
  id bigserial primary key,
  status varchar(20) not null,
  requested_limit int not null default 0,
  batch_size int not null default 12,
  total_symbols int not null default 0,
  processed_symbols int not null default 0,
  success_symbols int not null default 0,
  failed_symbols int not null default 0,
  inserted_bars bigint not null default 0,
  latest_trade_date date,
  message text,
  started_at timestamp,
  finished_at timestamp,
  duration_ms bigint,
  created_at timestamp not null default now()
);

create index if not exists idx_market_data_update_tasks_status
  on market_data_update_tasks(status, id desc);

create table if not exists market_data_update_failures (
  id bigserial primary key,
  task_id bigint not null references market_data_update_tasks(id) on delete cascade,
  symbol varchar(12) not null,
  name varchar(80),
  error_message text not null,
  created_at timestamp not null default now(),
  unique(task_id, symbol)
);

create table if not exists scan_backtest_tasks (
  id bigserial primary key,
  scan_task_id bigint not null references market_scan_tasks(id) on delete cascade,
  status varchar(20) not null,
  top_n int not null default 20,
  max_holding_days int not null default 10,
  total_symbols int not null default 0,
  processed_symbols int not null default 0,
  success_symbols int not null default 0,
  failed_symbols int not null default 0,
  avg_total_return numeric(16,8),
  avg_win_rate numeric(16,8),
  avg_max_drawdown numeric(16,8),
  positive_strategy_count int not null default 0,
  message text,
  started_at timestamp,
  finished_at timestamp,
  created_at timestamp not null default now()
);

create index if not exists idx_scan_backtest_tasks_scan
  on scan_backtest_tasks(scan_task_id, id desc);

create table if not exists scan_backtest_results (
  id bigserial primary key,
  backtest_task_id bigint not null references scan_backtest_tasks(id) on delete cascade,
  scan_task_id bigint not null references market_scan_tasks(id) on delete cascade,
  symbol varchar(12) not null,
  name varchar(80),
  scan_rank int,
  scan_score numeric(8,2),
  total_return numeric(16,8),
  max_drawdown numeric(16,8),
  win_rate numeric(16,8),
  profit_loss_ratio numeric(16,8),
  trade_count int,
  status varchar(20) not null,
  error_message text,
  created_at timestamp not null default now(),
  unique(backtest_task_id, symbol)
);

create index if not exists idx_scan_backtest_results_task
  on scan_backtest_results(backtest_task_id, scan_rank);
