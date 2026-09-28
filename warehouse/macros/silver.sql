{% macro silver(dataset) -%}
  {%- if target.type == 'duckdb' -%}
    read_parquet('{{ var("silver_path") }}/{{ dataset }}/*/*.parquet', union_by_name = true)
  {%- else -%}
    {{ target.database }}.silver.{{ dataset }}
  {%- endif -%}
{%- endmacro %}

{% macro months_between(later, earlier) -%}
  {%- if target.type == 'duckdb' -%}
    date_diff('month', {{ earlier }}, {{ later }})
  {%- else -%}
    datediff(month, {{ earlier }}, {{ later }})
  {%- endif -%}
{%- endmacro %}
