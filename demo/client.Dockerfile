FROM  astral/uv:python3.14-bookworm-slim

WORKDIR /

COPY client.py .

CMD ["uv", "run", "/client.py"]
