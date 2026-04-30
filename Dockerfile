FROM eclipse-temurin:17-jdk

WORKDIR /opt/render/project/src

COPY backend/src/Main.java backend/src/Main.java
COPY frontend frontend

RUN mkdir -p backend/out && javac -d backend/out backend/src/Main.java

CMD ["sh", "-c", "java -cp backend/out Main"]
