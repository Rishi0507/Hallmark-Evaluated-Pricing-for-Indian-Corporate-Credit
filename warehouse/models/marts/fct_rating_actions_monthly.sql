select date_trunc('month', effective_date) as month,
       case
           when lower(action) like '%downgrade%' then 'DOWNGRADE'
           when lower(action) like '%upgrade%' then 'UPGRADE'
           when lower(action) like '%withdraw%' then 'WITHDRAWN'
           when lower(action) like '%new%' or lower(action) like '%assign%' then 'NEW'
           else 'REAFFIRM_OR_OTHER'
       end as action_type,
       count(*) as actions,
       count(distinct isin) as isins
from {{ ref('stg_nsdl_ratings') }}
where effective_date >= date '2025-01-01' and rating_scale = 'LONG'
group by 1, 2
