FROM python:3.12-slim
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .
ENV INSIGHTS_WAREHOUSE_PATH=/data/warehouse.duckdb
USER 10001
EXPOSE 8090
CMD ["uvicorn", "insights_api.app:app", "--host", "0.0.0.0", "--port", "8090"]
