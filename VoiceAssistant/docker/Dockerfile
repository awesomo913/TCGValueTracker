FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    wget \
    unzip \
    python3 \
    python3-pip \
    && rm -rf /var/lib/apt/lists/*

# Android platform-tools (adb client only — no emulator, no full SDK)
RUN wget -q https://dl.google.com/android/repository/platform-tools-latest-linux.zip \
    -O /tmp/pt.zip \
    && unzip -q /tmp/pt.zip -d /opt \
    && rm /tmp/pt.zip
ENV PATH=$PATH:/opt/platform-tools

WORKDIR /workspace
COPY scripts/ scripts/
RUN chmod +x scripts/run_tests.sh

ENTRYPOINT ["scripts/run_tests.sh"]
