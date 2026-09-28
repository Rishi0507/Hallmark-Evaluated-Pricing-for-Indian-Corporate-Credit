with ranked as (
    select *, max(snapshot) over (partition by isin) as latest_snapshot
    from {{ silver('nsdl_ratings') }}
)
select isin, agency, rating_symbol, rating_scale, rating_notch, outlook, action, watch, is_current, withdrawn,
       coalesce(change_date, rating_date) as effective_date
from ranked
where snapshot = latest_snapshot
