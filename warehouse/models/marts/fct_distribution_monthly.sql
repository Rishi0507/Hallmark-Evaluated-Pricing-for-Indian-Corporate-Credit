select date_trunc('month', s.settlement_date) as month,
       s.denomination_class,
       coalesce(s.counterparty_class, 'UNKNOWN') as counterparty_class,
       coalesce(d.rating_bucket, 'UNKNOWN') as rating_bucket,
       coalesce(d.sector_group, 'UNKNOWN') as sector_group,
       count(*) as settlement_legs,
       count(distinct s.isin) as isins,
       count(distinct d.issuer_key) as issuers,
       sum(s.nominal_value_inr) / 1e7 as settled_face_crore,
       sum(s.nominal_value_inr * s.way)
           / nullif(sum(case when s.way is not null then s.nominal_value_inr end), 0) as value_weighted_yield
from {{ ref('stg_settlements') }} s
left join {{ ref('dim_security') }} d on d.isin = s.isin
where s.instrument_class = 'BOND' and s.status = 'SETTLED' and s.nominal_value_inr > 0
group by 1, 2, 3, 4, 5
