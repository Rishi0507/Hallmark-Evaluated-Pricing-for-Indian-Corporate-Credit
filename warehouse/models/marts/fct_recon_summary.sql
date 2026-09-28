select recon_type,
       coalesce(json_extract_string(details, '$.instrumentClass'), 'BOND') as instrument_class,
       result,
       coalesce(break_type, 'NONE') as break_type,
       count(*) as outcomes,
       sum(amount_lakh) / 100 as amount_crore
from {{ ref('stg_recon_outcomes') }}
group by 1, 2, 3, 4
union all
select 'YIELD_RECOMPUTE' as recon_type,
       'BOND' as instrument_class,
       result,
       coalesce(break_type, 'NONE') as break_type,
       count(*) as outcomes,
       sum(face_lakh) / 100 as amount_crore
from {{ ref('stg_recon_yield') }}
group by 1, 2, 3, 4
