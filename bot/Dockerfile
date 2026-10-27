FROM python:3.13-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY oda_bot ./oda_bot
# База и ключ подписи — в томе: при обновлении образа они не теряются
VOLUME /app/data
ENV ODA_DATA_DIR=/app/data
EXPOSE 8080
CMD ["python", "-m", "oda_bot"]
