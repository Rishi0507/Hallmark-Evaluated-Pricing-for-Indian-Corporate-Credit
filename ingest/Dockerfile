FROM python:3.12-slim
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY ingest/pyproject.toml ingest/README.md ./
COPY ingest/src ./src
COPY contracts /contracts
RUN pip install --no-cache-dir ".[kafka]"
ENV HALLMARK_SCHEMA_DIR=/contracts/schemas HALLMARK_DATA_DIR=/data
USER 10001
ENTRYPOINT ["hallmark-ingest"]
CMD ["daily", "--publish-to", "kafka"]
