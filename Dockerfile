FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY src/main/java ./src/main/java
RUN mkdir -p build/classes && javac --release 17 -encoding UTF-8 -d build/classes $(find src/main/java -name '*.java')

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/classes ./classes
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "classes", "com.flowguard.WebServer"]
