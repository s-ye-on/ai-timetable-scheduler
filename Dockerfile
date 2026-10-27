FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle/ gradle/
COPY src/main/java/ src/main/java/
COPY src/main/resources/application.yaml src/main/resources/application-prod.yaml src/main/resources/database.properties src/main/resources/
COPY src/main/resources/db/ src/main/resources/db/
COPY src/main/resources/static/demo.html src/main/resources/static/
# 로컬 JAR·설정 대신, 허용된 소스만으로 새로 빌드한다.
RUN chmod +x gradlew && ./gradlew --no-daemon --max-workers=1 \
    -Dorg.gradle.jvmargs="-Xmx512m -XX:MaxMetaspaceSize=384m" bootJar -x test \
    && mkdir /out && cp build/libs/*.jar /out/app.jar \
    && cd /out && jar xf app.jar BOOT-INF/lib \
    && cp BOOT-INF/lib/h2-*.jar /out/h2.jar

FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 campus && useradd --uid 10001 --gid campus --no-create-home campus
WORKDIR /opt/app
COPY --from=build /out/app.jar /out/h2.jar ./
COPY src/main/resources/db/schema-h2.sql ./schema-h2.sql
COPY --chmod=0555 deploy/app-entrypoint.sh ./entrypoint.sh
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/opt/app/entrypoint.sh"]
