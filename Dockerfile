# =====================================================================
#  后端多阶段构建：JDK 21 编译 -> JRE 21 运行
# =====================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先拷 pom，利用 Docker 层缓存预热依赖
COPY pom.xml .
COPY guarantee-common/pom.xml   guarantee-common/
COPY guarantee-auth/pom.xml     guarantee-auth/
COPY guarantee-system/pom.xml   guarantee-system/
COPY guarantee-order/pom.xml    guarantee-order/
COPY guarantee-analysis/pom.xml guarantee-analysis/
COPY guarantee-ai/pom.xml       guarantee-ai/
COPY guarantee-web/pom.xml      guarantee-web/
RUN mvn -B -ntp -q dependency:go-offline -DskipTests || true

COPY . .
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /build/guarantee-web/target/guarantee-ai-admin.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
