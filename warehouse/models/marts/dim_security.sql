with current_rating as (
    select isin,
           max(rating_notch) as composite_notch,
           max(rating_notch) - min(rating_notch) as rating_dispersion,
           count(distinct agency) as agencies
    from {{ ref('stg_nsdl_ratings') }}
    where is_current and rating_scale = 'LONG' and not withdrawn and rating_notch is not null
    group by isin
)
select g.isin,
       g.issuerName as issuer_name,
       g.issuerKey as issuer_key,
       g.ownership,
       g.sectorGroup as sector_group,
       g.couponRate as coupon_rate,
       g.couponBasis as coupon_basis,
       cast(g.maturityDate as date) as maturity_date,
       g.faceValue as face_value,
       g.denominationClass as denomination_class,
       g.pricingEligible as pricing_eligible,
       g.ineligibleReason as ineligible_reason,
       g.scheduleWarnings as schedule_warnings,
       g.conflictCount as source_conflicts,
       r.composite_notch,
       coalesce(r.rating_dispersion, 0) as rating_dispersion,
       coalesce(r.agencies, 0) as rating_agencies,
       case
           when r.composite_notch is null then 'UNRATED'
           when r.composite_notch = 1 then 'AAA'
           when r.composite_notch = 2 then 'AA+'
           when r.composite_notch = 3 then 'AA'
           when r.composite_notch = 4 then 'AA-'
           when r.composite_notch = 5 then 'A+'
           when r.composite_notch = 6 then 'A'
           when r.composite_notch = 7 then 'A-'
           when r.composite_notch <= 10 then 'BBB'
           when r.composite_notch < 20 then 'SUB_IG'
           else 'D'
       end as rating_bucket
from {{ ref('stg_golden') }} g
left join current_rating r on r.isin = g.isin
