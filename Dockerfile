FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/dependency

FROM eclipse-temurin:21-jre

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends chromium curl novnc openbox supervisor websockify x11vnc xvfb \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system webtoapi \
    && useradd --system --gid webtoapi --home-dir /app --create-home webtoapi \
    && install -d -o webtoapi -g webtoapi /app/target/classes /app/target/dependency /app/data /app/logs

COPY --from=build /build/target/classes /app/target/classes
COPY --from=build /build/target/dependency /app/target/dependency
COPY docker/supervisor/gemini-chrome.conf /etc/supervisor/conf.d/gemini-chrome.conf
COPY docker/supervisor/webtoapi.conf /etc/supervisor/conf.d/webtoapi.conf
COPY docker/supervisor/desktop.conf /etc/supervisor/conf.d/desktop.conf
COPY docker/start-vnc.sh /usr/local/bin/start-vnc.sh
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
COPY docker/supervisord.conf /etc/supervisor/supervisord.conf

RUN chmod 0755 /usr/local/bin/start-vnc.sh /usr/local/bin/entrypoint.sh \
    && if [ -f /usr/share/novnc/vnc.html ]; then \
         ln -sf vnc.html /usr/share/novnc/index.html; \
       fi \
    && chown -R webtoapi:webtoapi /app/target /app/data /app/logs

EXPOSE 60000 5910 6082
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
