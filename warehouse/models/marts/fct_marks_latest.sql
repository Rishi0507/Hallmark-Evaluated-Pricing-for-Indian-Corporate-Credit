select m.*,
       d.issuer_name,
       d.issuer_key,
       d.denomination_class,
       d.coupon_rate,
       d.maturity_date
from {{ ref('stg_marks') }} m
left join {{ ref('dim_security') }} d on d.isin = m.isin
where m.valuation_date = (select max(valuation_date) from {{ ref('stg_marks') }})
