with curve as (
    select as_of,
           max(case when tenor_years = 1 then par_yield_annualised end) as gsec_1y,
           max(case when tenor_years = 5 then par_yield_annualised end) as gsec_5y,
           max(case when tenor_years = 10 then par_yield_annualised end) as gsec_10y
    from {{ ref('stg_par_curve') }}
    group by as_of
),
fx as (
    select as_of, inr_per_unit as usd_inr
    from {{ ref('stg_fx_reference') }}
    where currency = 'USD'
),
reported as (
    select trade_date, count(*) as bonds_traded, sum(face_lakh) / 100 as reported_face_crore
    from {{ ref('stg_trades_reported') }}
    where instrument_class = 'BOND'
    group by trade_date
)
select r.trade_date as business_date,
       r.bonds_traded,
       r.reported_face_crore,
       c.gsec_1y,
       c.gsec_5y,
       c.gsec_10y,
       fx.usd_inr
from reported r
left join curve c on c.as_of = r.trade_date
left join fx on fx.as_of = r.trade_date
