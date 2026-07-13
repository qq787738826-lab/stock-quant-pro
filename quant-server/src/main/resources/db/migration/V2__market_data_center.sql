alter table securities
  add column if not exists data_source varchar(80),
  add column if not exists latest_price numeric(18,4),
  add column if not exists latest_trade_date date;

create index if not exists idx_securities_active_board
  on securities(is_active, is_st, board, symbol);

create table if not exists market_sync_runs (
  id bigserial primary key,
  sync_type varchar(30) not null,
  status varchar(20) not null,
  item_count int not null default 0,
  message text,
  started_at timestamp,
  finished_at timestamp,
  created_at timestamp not null default now()
);

create table if not exists market_scan_tasks (
  id bigserial primary key,
  status varchar(20) not null,
  requested_limit int not null default 0,
  batch_size int not null default 12,
  result_limit int not null default 50,
  total_symbols int not null default 0,
  processed_symbols int not null default 0,
  success_symbols int not null default 0,
  failed_symbols int not null default 0,
  selected_count int not null default 0,
  message text,
  started_at timestamp,
  finished_at timestamp,
  created_at timestamp not null default now()
);

create index if not exists idx_market_scan_tasks_status
  on market_scan_tasks(status, id desc);

create table if not exists market_scan_results (
  id bigserial primary key,
  task_id bigint not null references market_scan_tasks(id) on delete cascade,
  rank_no int not null default 0,
  symbol varchar(12) not null,
  name varchar(80) not null,
  trade_date date not null,
  score numeric(8,2) not null,
  signal_level varchar(20) not null,
  risk_level varchar(20) not null,
  latest_close numeric(18,4) not null,
  buy_low numeric(18,4),
  buy_high numeric(18,4),
  stop_loss numeric(18,4),
  target1 numeric(18,4),
  target2 numeric(18,4),
  suggested_weight numeric(8,4),
  data_source varchar(100),
  summary text,
  metrics jsonb not null default '{}'::jsonb,
  bullish jsonb not null default '[]'::jsonb,
  bearish jsonb not null default '[]'::jsonb,
  created_at timestamp not null default now(),
  unique(task_id, symbol)
);

create index if not exists idx_market_scan_results_task_rank
  on market_scan_results(task_id, rank_no);
create index if not exists idx_market_scan_results_score
  on market_scan_results(task_id, score desc);
