FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN mkdir -p /root/.m2 \
    && cp .mvn/settings.xml /root/.m2/settings.xml \
    && mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN sed -i \
      -e 's@http://archive.ubuntu.com/ubuntu/@http://mirrors.aliyun.com/ubuntu/@g' \
      -e 's@http://security.ubuntu.com/ubuntu/@http://mirrors.aliyun.com/ubuntu/@g' \
      /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
CMD ["java", "-Dserver.address=0.0.0.0", "-Dspring.main.lazy-initialization=true", "-Dspring.jmx.enabled=false", "-jar", "app.jar"]
