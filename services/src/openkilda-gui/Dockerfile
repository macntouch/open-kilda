FROM kilda/base-ubuntu

COPY src/main/resources/application.properties src/main/resources/logback.xml target/openkilda-gui.jar /app/
WORKDIR /app

EXPOSE 1010

CMD ["java", "-XX:+PrintFlagsFinal", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCGroupMemoryLimitForHeap", "-jar", "openkilda-gui.jar"]
