# All a Linux reference depends on besides the app: the JDK, the fonts its text is drawn with, and a display, without
# which AWT lists no fonts and the look falls back to the default one.
FROM eclipse-temurin:21-jdk-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        fonts-cantarell libegl1 libxext6 libxi6 libxrender1 libxtst6 rsync xauth xvfb \
    && rm -rf /var/lib/apt/lists
