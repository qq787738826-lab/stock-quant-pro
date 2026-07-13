alter table portfolio_accounts
  add column if not exists frozen_cash numeric(20,2) not null default 0,
  add column if not exists realized_pnl numeric(20,2) not null default 0,
  add column if not exists total_fees numeric(20,2) not null default 0,
  add column if not exists updated_at timestamp not null default now();

alter table trade_plans
  add column if not exists name varchar(80),
  add column if not exists source_task_id bigint,
  add column if not exists source_result_id bigint,
  add column if not exists quantity_hint int,
  add column if not exists created_at timestamp not null default now(),
  add column if not exists updated_at timestamp not null default now();

alter table positions
  add column if not exists stop_loss numeric(18,4),
  add column if not exists target_price numeric(18,4),
  add column if not exists trailing_stop_pct numeric(8,4) not null default 0.04,
  add column if not exists highest_price numeric(18,4),
  add column if not exists source_plan_id bigint,
  add column if not exists opened_at timestamp,
  add column if not exists last_buy_date date,
  add column if not exists updated_at timestamp not null default now();

alter table manual_orders
  add column if not exists account_id bigint not null default 1,
  add column if not exists name varchar(80),
  add column if not exists trade_plan_id bigint,
  add column if not exists client_order_no varchar(64),
  add column if not exists filled_quantity int not null default 0,
  add column if not exists filled_price numeric(18,4),
  add column if not exists gross_amount numeric(20,2),
  add column if not exists commission numeric(20,2) not null default 0,
  add column if not exists stamp_duty numeric(20,2) not null default 0,
  add column if not exists transfer_fee numeric(20,2) not null default 0,
  add column if not exists net_amount numeric(20,2),
  add column if not exists frozen_amount numeric(20,2) not null default 0,
  add column if not exists frozen_quantity int not null default 0,
  add column if not exists reject_reason text,
  add column if not exists executed_at timestamp,
  add column if not exists cancelled_at timestamp;

create unique index if not exists uk_manual_orders_client_no
  on manual_orders(client_order_no)
  where client_order_no is not null;

create index if not exists idx_manual_orders_status_created
  on manual_orders(status, created_at desc);

create table if not exists simulated_trades (
  id bigserial primary key,
  order_id bigint not null unique references manual_orders(id),
  account_id bigint not null references portfolio_accounts(id),
  trade_plan_id bigint,
  symbol varchar(12) not null,
  name varchar(80),
  side varchar(8) not null,
  quantity int not null,
  price numeric(18,4) not null,
  gross_amount numeric(20,2) not null,
  commission numeric(20,2) not null default 0,
  stamp_duty numeric(20,2) not null default 0,
  transfer_fee numeric(20,2) not null default 0,
  net_amount numeric(20,2) not null,
  realized_pnl numeric(20,2) not null default 0,
  trade_date date not null,
  trade_time timestamp not null default now()
);

create index if not exists idx_simulated_trades_date
  on simulated_trades(trade_date desc, id desc);
create index if not exists idx_simulated_trades_symbol
  on simulated_trades(symbol, trade_date desc);

create table if not exists account_equity_snapshots (
  account_id bigint not null references portfolio_accounts(id),
  snapshot_date date not null,
  cash numeric(20,2) not null,
  frozen_cash numeric(20,2) not null default 0,
  market_value numeric(20,2) not null,
  total_asset numeric(20,2) not null,
  realized_pnl numeric(20,2) not null,
  unrealized_pnl numeric(20,2) not null,
  total_return numeric(16,8) not null,
  created_at timestamp not null default now(),
  updated_at timestamp not null default now(),
  primary key(account_id, snapshot_date)
);

alter table risk_events
  add column if not exists account_id bigint not null default 1,
  add column if not exists event_key varchar(160),
  add column if not exists current_price numeric(18,4),
  add column if not exists trigger_price numeric(18,4),
  add column if not exists resolved_at timestamp;

create unique index if not exists uk_risk_events_event_key
  on risk_events(event_key);

insert into app_settings(setting_key, setting_value) values
  ('portfolio.max_positions', '5'),
  ('portfolio.max_position_weight', '0.20'),
  ('portfolio.commission_rate', '0.0003'),
  ('portfolio.minimum_commission', '5'),
  ('portfolio.stamp_duty_rate', '0.0005'),
  ('portfolio.transfer_fee_rate', '0.00001'),
  ('portfolio.default_trailing_stop_pct', '0.04')
on conflict(setting_key) do nothing;

update positions
set highest_price = greatest(coalesce(highest_price, 0), last_price, average_cost),
    opened_at = coalesce(opened_at, now()),
    last_buy_date = coalesce(last_buy_date, date '2000-01-01'),
    updated_at = now()
where highest_price is null or opened_at is null or last_buy_date is null;
