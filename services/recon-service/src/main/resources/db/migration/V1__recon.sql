create table processed_event (
    event_id     varchar(64) primary key,
    processed_at timestamp   not null
);

create table reported_trade (
    isin             varchar(12) not null,
    trade_date       date        not null,
    instrument_class varchar(8)  not null,
    total_face_lakh  double precision not null,
    wap              double precision,
    way              double precision,
    primary key (isin, trade_date)
);
create index ix_rep_date on reported_trade (trade_date);

create table settlement_leg (
    event_id           varchar(64) primary key,
    isin               varchar(12) not null,
    trade_date         date        not null,
    settlement_date    date        not null,
    instrument_class   varchar(8)  not null,
    counterparty_class varchar(16),
    status             varchar(8)  not null,
    nominal_inr        double precision not null,
    wap                double precision
);
create index ix_leg_trade_date on settlement_leg (trade_date);
create index ix_leg_settle_date on settlement_leg (settlement_date);

create table source_record (
    isin       varchar(12)      not null,
    source     varchar(16)      not null,
    payload    varchar(1048576) not null,
    updated_at timestamp        not null,
    primary key (isin, source)
);

create table recon_break (
    break_id      varchar(80)  primary key,
    recon_type    varchar(24)  not null,
    break_type    varchar(40)  not null,
    isin          varchar(12)  not null,
    business_date date         not null,
    status        varchar(16)  not null,
    severity      varchar(8)   not null,
    amount_lakh   double precision,
    details       varchar(8192) not null,
    first_seen    date         not null,
    last_seen     date         not null,
    resolved_on   date,
    resolution    varchar(400),
    updated_by    varchar(80),
    updated_at    timestamp    not null
);
create index ix_break_status on recon_break (status, recon_type);
create index ix_break_date on recon_break (business_date);

create table recon_run (
    recon_type    varchar(24) not null,
    business_date date        not null,
    evaluated     integer     not null,
    matched       integer     not null,
    explained     integer     not null,
    pending       integer     not null,
    breaks        integer     not null,
    run_on        date        not null,
    primary key (recon_type, business_date)
);

create table eod_control (
    business_date        date primary key,
    expected_reported    integer     not null,
    expected_settlements integer     not null,
    status               varchar(12) not null,
    received_at          timestamp   not null,
    completed_at         timestamp
);
