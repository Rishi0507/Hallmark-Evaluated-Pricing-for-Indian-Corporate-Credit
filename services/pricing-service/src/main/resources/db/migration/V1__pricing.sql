create table processed_event (
    event_id     varchar(64) primary key,
    processed_at timestamp   not null
);

create table security (
    isin             varchar(12) primary key,
    issuer_key       varchar(96),
    issuer_name      varchar(300),
    sector_group     varchar(64),
    pricing_eligible boolean          not null,
    golden           varchar(1048576) not null,
    updated_at       timestamp        not null
);

create table risk_free_curve (
    as_of      date primary key,
    points     varchar(65536) not null,
    updated_at timestamp      not null
);

create table observation (
    isin             varchar(12) not null,
    trade_date       date        not null,
    clean_price      double precision not null,
    yield_pct        double precision not null,
    face_lakh        double precision not null,
    institutional    boolean     not null,
    clean            boolean     not null,
    flags            varchar(200),
    z_spread_bps     double precision,
    tenor_years      double precision,
    rating_bucket    varchar(16),
    sector_group     varchar(64),
    issuer_key       varchar(96),
    model_spread_bps double precision,
    risk_free_yield_pct double precision,
    primary key (isin, trade_date)
);
create index ix_observation_date on observation (trade_date);

create table mark (
    valuation_date    date        not null,
    isin              varchar(12) not null,
    method            varchar(16) not null,
    clean_price       double precision not null,
    dirty_price       double precision not null,
    accrued           double precision not null,
    yield_pct         double precision not null,
    z_spread_bps      double precision not null,
    g_spread_bps      double precision,
    modified_duration double precision not null,
    tenor_years       double precision not null,
    rating_bucket     varchar(16) not null,
    sector_group      varchar(64),
    model_spread_bps  double precision not null,
    confidence        double precision not null,
    evidence          varchar(8192),
    primary key (valuation_date, isin)
);
create index ix_mark_isin on mark (isin, valuation_date);

create table curve_fit (
    valuation_date date        not null,
    bucket         varchar(16) not null,
    level_bps      double precision not null,
    slope_bps      double precision not null,
    scale_bps      double precision not null,
    effective_n    double precision not null,
    source         varchar(16) not null,
    primary key (valuation_date, bucket)
);

create table sector_offset (
    valuation_date date        not null,
    sector_group   varchar(64) not null,
    offset_bps     double precision not null,
    weight         double precision not null,
    observations   integer     not null,
    primary key (valuation_date, sector_group)
);

create table backtest (
    trade_date               date        not null,
    isin                     varchar(12) not null,
    method                   varchar(16) not null,
    rating_bucket            varchar(16) not null,
    sector_group             varchar(64),
    predicted_yield_pct      double precision not null,
    observed_yield_pct       double precision not null,
    error_bps                double precision not null,
    last_yield_error_bps     double precision,
    carried_spread_error_bps double precision,
    confidence               double precision not null,
    primary key (trade_date, isin)
);

create table eod_control (
    business_date         date primary key,
    expected_observations integer     not null,
    status                varchar(12) not null,
    received_at           timestamp   not null,
    completed_at          timestamp,
    curve_date            date,
    marks                 integer,
    predictions           integer
);
