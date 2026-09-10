create table processed_event (
    event_id     varchar(64) primary key,
    processed_at timestamp   not null
);

create table source_record (
    isin          varchar(12)      not null,
    source        varchar(16)      not null,
    snapshot_date date,
    payload       varchar(1048576) not null,
    received_at   timestamp        not null,
    primary key (isin, source)
);

create table golden_security (
    isin               varchar(12) primary key,
    issuer_key         varchar(96),
    issuer_name        varchar(300),
    sector_group       varchar(64)      not null,
    denomination_class varchar(16)      not null,
    maturity_date      date,
    coupon_rate        double precision,
    composite_notch    integer,
    composite_symbol   varchar(8),
    rating_bucket      varchar(16)      not null,
    rating_dispersion  integer          not null,
    pricing_eligible   boolean          not null,
    ineligible_reason  varchar(32),
    conflict_count     integer          not null,
    content_hash       varchar(64)      not null,
    golden             varchar(1048576) not null,
    updated_at         timestamp        not null
);
create index ix_golden_issuer on golden_security (issuer_key);
create index ix_golden_sector on golden_security (sector_group, rating_bucket);

create table rating_history (
    isin           varchar(12) not null,
    agency         varchar(32) not null,
    raw_rating     varchar(120),
    symbol         varchar(8),
    notch          integer,
    outlook        varchar(40),
    action         varchar(60),
    effective_date date,
    rating_date    date,
    watch          varchar(60),
    is_current     boolean     not null,
    withdrawn      boolean     not null
);
create index ix_rating_isin on rating_history (isin);
create index ix_rating_effective on rating_history (effective_date);
