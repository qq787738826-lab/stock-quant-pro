create table if not exists securities (
  symbol varchar(12) primary key, name varchar(80) not null, exchange varchar(8) not null,
  board varchar(20) not null default 'MAIN', industry varchar(80), list_date date,
  is_st boolean not null default false, is_active boolean not null default true, updated_at timestamp not null default now()
);
create table if not exists daily_bars (
  symbol varchar(12) not null references securities(symbol), trade_date date not null,
  open numeric(18,4) not null, high numeric(18,4) not null, low numeric(18,4) not null, close numeric(18,4) not null,
  volume bigint not null, amount numeric(24,4), turnover_rate numeric(10,4), adjust_type varchar(8) not null default 'QFQ',
  primary key(symbol, trade_date, adjust_type)
);
create index if not exists idx_daily_bars_date on daily_bars(trade_date desc);
create table if not exists signals (
  id bigserial primary key, symbol varchar(12) not null, signal_date date not null, action varchar(16) not null,
  score numeric(8,2) not null, reference_price numeric(18,4), reasons jsonb, risks jsonb, strategy_code varchar(60) not null, created_at timestamp not null default now()
);
create index if not exists idx_signals_date_score on signals(signal_date desc, score desc);
create table if not exists trade_plans (
  id bigserial primary key, symbol varchar(12) not null, plan_date date not null, score numeric(8,2), buy_low numeric(18,4), buy_high numeric(18,4),
  stop_loss numeric(18,4), target1 numeric(18,4), target2 numeric(18,4), suggested_weight numeric(8,4), valid_days int, status varchar(20) default 'ACTIVE', rationale text
);
create table if not exists portfolio_accounts (
  id bigint primary key, name varchar(80) not null, initial_capital numeric(20,2) not null, cash numeric(20,2) not null, created_at timestamp not null default now()
);
insert into portfolio_accounts(id,name,initial_capital,cash) values (1,'默认模拟账户',100000,100000) on conflict(id) do nothing;
create table if not exists positions (
  id bigserial primary key, account_id bigint not null references portfolio_accounts(id), symbol varchar(12) not null,
  quantity int not null, available_quantity int not null, average_cost numeric(18,4) not null, last_price numeric(18,4) not null,
  market_value numeric(20,2) generated always as (quantity * last_price) stored,
  unrealized_pnl numeric(20,2) generated always as (quantity * (last_price-average_cost)) stored,
  unique(account_id,symbol)
);
create table if not exists manual_orders (
  id bigserial primary key, symbol varchar(12) not null, side varchar(8) not null, quantity int not null, limit_price numeric(18,4) not null,
  status varchar(30) not null, created_at timestamp not null default now(), confirmed_at timestamp, broker_note text
);
create table if not exists backtest_runs (
  id bigserial primary key, strategy_code varchar(60) not null, symbol varchar(12), params jsonb not null, initial_capital numeric(20,2), final_capital numeric(20,2),
  total_return numeric(12,6), max_drawdown numeric(12,6), win_rate numeric(12,6), trade_count int, started_at timestamp, finished_at timestamp
);
create table if not exists risk_events (
  id bigserial primary key, event_time timestamp not null default now(), level varchar(16) not null, event_type varchar(50) not null, symbol varchar(12), message text not null, resolved boolean not null default false
);
create table if not exists ai_reports (
  id bigserial primary key, symbol varchar(12), report_type varchar(40) not null, provider varchar(40) not null, content jsonb not null, created_at timestamp not null default now()
);
create table if not exists app_settings (
  setting_key varchar(100) primary key, setting_value text, updated_at timestamp not null default now()
);
