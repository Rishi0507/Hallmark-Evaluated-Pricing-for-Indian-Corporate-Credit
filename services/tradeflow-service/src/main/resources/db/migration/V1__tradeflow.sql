create table processed_event (
    event_id     varchar(64) primary key,
    processed_at timestamp   not null
);

create table security_ref (
    isin               varchar(12) primary key,
    issuer_key         varchar(96),
    sector_group       varchar(64),
    denomination_class varchar(16),
    pricing_eligible   boolean          not null,
    golden             varchar(1048576) not null,
    updated_at         timestamp        not null
);

create table reported_trade (
    isin                  varchar(12) not null,
    trade_date            date        not null,
    instrument_class      varchar(8)  not null,
    last_price            double precision,
    last_yield            double precision,
    last_trade_value_lakh double precision,
    total_face_lakh       double precision not null,
    wap                   double precision,
    way                   double precision,
    primary key (isin, trade_date)
);
create index ix_reported_date on reported_trade (trade_date);

create table settlement_leg (
    event_id           varchar(64) primary key,
    isin               varchar(12) not null,
    trade_date         date        not null,
    settlement_date    date        not null,
    instrument_class   varchar(8)  not null,
    listing            varchar(12),
    counterparty_class varchar(16),
    status             varchar(8)  not null,
    quantity           bigint,
    nominal_inr        double precision not null,
    wap                double precision,
    way                double precision,
    denomination_class varchar(16)
);
create index ix_leg_trade on settlement_leg (isin, trade_date);
create index ix_leg_settlement on settlement_leg (settlement_date);

create table trade_lifecycle (
    isin                 varchar(12) not null,
    trade_date           date        not null,
    instrument_class     varchar(8),
    state                varchar(24) not null,
    reported_face_lakh   double precision,
    settled_face_lakh    double precision not null,
    failed_face_lakh     double precision not null,
    settled_share        double precision,
    last_settlement_date date,
    evaluated_on         date        not null,
    primary key (isin, trade_date)
);
create index ix_lifecycle_state on trade_lifecycle (trade_date, state);

create table observation (
    isin         varchar(12) not null,
    trade_date   date        not null,
    size_class   varchar(16) not null,
    clean        boolean     not null,
    payload      varchar(65536) not null,
    primary key (isin, trade_date)
);

create table eod_control (
    business_date        date primary key,
    expected_reported    integer   not null,
    expected_settlements integer   not null,
    status               varchar(12) not null,
    received_at          timestamp not null,
    completed_at         timestamp
);
