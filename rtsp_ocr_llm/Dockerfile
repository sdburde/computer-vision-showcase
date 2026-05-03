FROM nvidia/cuda:11.8.0-cudnn8-runtime-ubuntu22.04
ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True
# --- System dependencies ---
RUN apt-get update && apt-get install -y \
    build-essential \
    wget \
    curl \
    git \
    zlib1g-dev \
    libffi-dev \
    libssl-dev \
    libbz2-dev \
    libreadline-dev \
    libsqlite3-dev \
    liblzma-dev \
    libncurses5-dev \
    libgdbm-dev \
    libnss3-dev \
    libtinfo-dev \
    libgl1-mesa-glx \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*
# --- Build & install Python 3.12.2 ---
RUN wget https://www.python.org/ftp/python/3.12.2/Python-3.12.2.tgz -O /tmp/Python.tgz && \
    tar -xzf /tmp/Python.tgz -C /tmp && \
    cd /tmp/Python-3.12.2 && \
    ./configure --enable-optimizations && \
    make -j"$(nproc)" && \
    make install && \
    ln -sf /usr/local/bin/python3.12 /usr/bin/python3 && \
    ln -sf /usr/local/bin/pip3.12 /usr/bin/pip3 && \
    python3 --version && pip3 --version

WORKDIR /app

# --- Python dependencies ---
RUN pip3 install --upgrade pip setuptools wheel

# App requirements (fastapi, uvicorn, opencv, requests, etc.)
COPY requirements.txt .
RUN pip3 install --no-cache-dir -r requirements.txt
RUN pip3 install --no-cache-dir "PyYAML>=6.0"

# PaddlePaddle-GPU for CUDA 11.8
RUN pip3 install paddlepaddle-gpu==3.2.1 -i https://www.paddlepaddle.org.cn/packages/stable/cu118/

RUN pip3 install --no-cache-dir paddleocr

RUN apt-get update && apt-get install --yes ffmpeg && rm -rf /var/lib/apt/lists/*
RUN pip3 install uvicorn




# LangGraph agentic pipeline (added after heavy deps to keep rebuild fast)
RUN pip3 install --no-cache-dir langgraph langchain-openai langchain-core

COPY app/ .

EXPOSE 7894

CMD ["python3", "-m", "uvicorn", "main:app", \
     "--host", "0.0.0.0", "--port", "7894", "--workers", "1"]
