with bounds as (
    select max(settlement_date) as last_settlement from {{ ref('stg_settlements') }}
),
retail_flow as (
    select s.isin,
           sum(s.nominal_value_inr) / 1e7 as retail_face_crore,
           count(*) as retail_legs,
           sum(s.nominal_value_inr * s.way) / nullif(sum(s.nominal_value_inr), 0) as retail_yield
    from {{ ref('stg_settlements') }} s, bounds b
    where s.status = 'SETTLED' and s.instrument_class = 'BOND' and s.denomination_class = 'RETAIL'
      and s.settlement_date > b.last_settlement - 90
    group by s.isin
),
fails as (
    select s.isin, count(*) as fails_180d
    from {{ ref('stg_settlements') }} s, bounds b
    where s.status = 'FAILED' and s.settlement_date > b.last_settlement - 180
    group by s.isin
),
actions as (
    select r.isin,
           sum(case when lower(r.action) like '%downgrade%' then 1 else 0 end) as downgrades_365d,
           sum(case when lower(coalesce(r.watch, '')) like '%negative%'
                     or lower(coalesce(r.outlook, '')) = 'negative' then 1 else 0 end) as negative_signals
    from {{ ref('stg_nsdl_ratings') }} r, bounds b
    where r.effective_date > b.last_settlement - 365
    group by r.isin
)
select d.issuer_key,
       min(d.issuer_name) as issuer_name,
       min(d.sector_group) as sector_group,
       max(d.composite_notch) as worst_notch,
       max(d.rating_bucket) as rating_bucket,
       sum(f.retail_face_crore) as retail_face_crore_90d,
       sum(f.retail_legs) as retail_legs_90d,
       sum(f.retail_face_crore * f.retail_yield) / nullif(sum(f.retail_face_crore), 0) as retail_yield,
       coalesce(sum(fl.fails_180d), 0) as fails_180d,
       coalesce(sum(a.downgrades_365d), 0) as downgrades_365d,
       coalesce(sum(a.negative_signals), 0) as negative_rating_signals
from retail_flow f
join {{ ref('dim_security') }} d on d.isin = f.isin
left join fails fl on fl.isin = f.isin
left join actions a on a.isin = f.isin
where d.issuer_key is not null
group by d.issuer_key
